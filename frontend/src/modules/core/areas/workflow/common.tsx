import { Select, Space, Tooltip, Typography } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { StatusTag } from '@/shared/components';
import { formatDate, formatDateTime, localizedName } from '@/shared/format';
import { useUserSearch } from './queries';
import type { Summary, UserRef } from './api';
import './i18n';

/** Bilingual process name of a task / request row. */
export function processName(row: { processNameVi: string; processNameEn?: string | null }, language: string): string {
  return localizedName({ nameVi: row.processNameVi, nameEn: row.processNameEn }, language);
}

function formatSummaryValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? '✓' : '✗';
  if (typeof value === 'number') return new Intl.NumberFormat('vi-VN').format(value);
  if (typeof value === 'string') {
    if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return formatDate(value);
    if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) return formatDateTime(value);
    return value;
  }
  if (Array.isArray(value)) return value.map(formatSummaryValue).join(', ');
  return JSON.stringify(value);
}

/** Renders the request `summary` map (module-defined key/value pairs) as compact key: value lines. */
export function SummaryCell({ summary, max = 4 }: { summary: Summary | null | undefined; max?: number }) {
  const entries = Object.entries(summary ?? {});
  if (entries.length === 0) return <Typography.Text type="secondary">—</Typography.Text>;
  const shown = entries.slice(0, max);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 360 }}>
      {shown.map(([k, v]) => (
        <Typography.Text key={k} ellipsis style={{ fontSize: 13 }}>
          <Typography.Text type="secondary">{k}: </Typography.Text>
          {formatSummaryValue(v)}
        </Typography.Text>
      ))}
      {entries.length > max ? <Typography.Text type="secondary">+{entries.length - max}</Typography.Text> : null}
    </div>
  );
}

/** Full key/value table of a summary map (detail page). */
export function SummaryList({ summary }: { summary: Summary | null | undefined }) {
  const entries = Object.entries(summary ?? {});
  if (entries.length === 0) return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', columnGap: 16, rowGap: 4, margin: 0 }}>
      {entries.map(([k, v]) => (
        <div key={k} style={{ display: 'contents' }}>
          <dt style={{ color: 'rgba(0,0,0,.45)' }}>{k}</dt>
          <dd style={{ margin: 0, wordBreak: 'break-word' }}>{formatSummaryValue(v)}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * Summary keys that carry a human-readable name of the subject record, best first. The owning module writes them
 * when it starts the request (`WorkflowService.StartRequest.summary`, e.g. `employeeName` / `employeeNo`).
 */
const SUBJECT_NAME_KEYS = [
  'employeeName',
  'personName',
  'candidateName',
  'subjectName',
  'fullName',
  'name',
  'title',
  'employeeNo',
  'code',
] as const;

/** The subject's display name taken from the request summary, or `null` when the summary carries none. */
export function subjectName(summary: Summary | null | undefined): string | null {
  for (const key of SUBJECT_NAME_KEYS) {
    const value = summary?.[key];
    if (typeof value === 'string' && value.trim() !== '') return value.trim();
    if (typeof value === 'number') return String(value);
  }
  return null;
}

/**
 * The record a request is about: its name from the summary with the record type as a hint, never the raw id —
 * the id stays available as the tooltip for support. Renders nothing when the request has no subject.
 */
export function SubjectLabel({
  subjectType,
  subjectId,
  summary,
}: {
  subjectType?: string | null;
  subjectId?: UUID | null;
  summary: Summary | null | undefined;
}) {
  const { t } = useTranslation('core-workflow');
  if (!subjectType) return null;
  const typeLabel = t(`subjectType.${subjectType}`, { defaultValue: subjectType });
  const name = subjectName(summary);
  const body = (
    <span data-testid="subject">
      <Space size={6} wrap>
        {name ? <Typography.Text>{name}</Typography.Text> : null}
        <Typography.Text type="secondary">{typeLabel}</Typography.Text>
      </Space>
    </span>
  );
  return subjectId ? <Tooltip title={subjectId}>{body}</Tooltip> : body;
}

/** Instance / task / callback status with the workflow-specific labels on top of the common ones. */
export function WorkflowStatusTag({ status }: { status: string | null | undefined }) {
  const { t, i18n } = useTranslation('core-workflow');
  if (!status) return null;
  const key = `status.${status}`;
  const labels = i18n.exists(key, { ns: 'core-workflow' }) ? { [status]: t(key) } : undefined;
  return <StatusTag status={status} labels={labels} />;
}

/** Translated action label (`action.<ACTION>`), falling back to the code. */
export function useActionLabel() {
  const { t } = useTranslation('core-workflow');
  return (action: string) => t(`action.${action}`, { defaultValue: action });
}

export function userLabel(u: UserRef): string {
  return u.displayName ? `${u.displayName} (${u.login})` : u.login;
}

/** Searchable user select backed by `GET /core/users?q=`. */
export function UserPicker({
  value,
  onChange,
  id,
  placeholder,
  disabled,
}: {
  value?: UUID;
  onChange?: (value: UUID | undefined, user?: UserRef) => void;
  id?: string;
  placeholder?: string;
  disabled?: boolean;
}) {
  const { t } = useTranslation('core-workflow');
  const [q, setQ] = useState('');
  const users = useUserSearch(q);
  const options = (users.data?.items ?? []).map((u) => ({ value: u.id, label: userLabel(u), user: u }));
  return (
    <Select<UUID, { value: UUID; label: string; user: UserRef }>
      id={id}
      showSearch
      allowClear
      disabled={disabled}
      value={value}
      filterOption={false}
      onSearch={setQ}
      loading={users.isFetching}
      notFoundContent={q ? t('reassign.noUsers') : null}
      placeholder={placeholder ?? t('reassign.searchUser')}
      options={options}
      onChange={(v, option) => onChange?.(v, Array.isArray(option) ? option[0]?.user : option?.user)}
      style={{ width: '100%' }}
    />
  );
}
