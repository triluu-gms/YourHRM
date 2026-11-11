import { useState, type CSSProperties, type ReactNode } from 'react';
import { DatePicker, Form, Modal, Select, Space, Tag, Tooltip, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { ISODate, UUID } from '@/shared/api';
import { isProblem } from '@/shared/api';
import { ConfirmButton, ProblemAlert } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatValidity, ISO_DATE, localizedName, today } from '@/shared/format';
import type { AdminUnit, LegalEntity, MinWageRegion } from './api';
import { useAdminUnit, useAdminUnitSearch, useLegalEntities } from './queries';
import { EMPLOYER_NS } from './i18n';

const adminUnitLabel = (u: AdminUnit, language: string) =>
  `${localizedName(u, language)} (${u.code})`;

/** Searchable admin-unit picker (province / ward, BR-CORE-080); controlled by `Form.Item`. */
export function AdminUnitSelect({
  value,
  onChange,
  asOf,
  id,
}: {
  value?: string | null;
  onChange?: (code: string | null) => void;
  asOf?: ISODate;
  id?: string;
}) {
  const { t, i18n } = useTranslation(EMPLOYER_NS);
  const [term, setTerm] = useState('');
  const search = useAdminUnitSearch(term, asOf);
  const current = useAdminUnit(value);
  const options = (search.data ?? []).map((u) => ({
    value: u.code,
    label: `${adminUnitLabel(u, i18n.language)} · ${t(`adminUnit.level.${u.level}`, { defaultValue: u.level })}`,
  }));
  if (value && !options.some((o) => o.value === value)) {
    options.unshift({ value, label: current.data ? adminUnitLabel(current.data, i18n.language) : value });
  }
  return (
    <Select
      id={id}
      showSearch
      allowClear
      filterOption={false}
      value={value ?? undefined}
      placeholder={t('adminUnit.placeholder')}
      onSearch={setTerm}
      onChange={(v) => onChange?.(v ?? null)}
      loading={search.isFetching}
      notFoundContent={term.trim().length < 2 ? t('adminUnit.typeToSearch') : t('adminUnit.noResults')}
      options={options}
    />
  );
}

/** Read-only admin-unit name for detail pages. */
export function AdminUnitName({ code }: { code?: string | null }) {
  const { i18n } = useTranslation();
  const unit = useAdminUnit(code);
  if (!code) return null;
  return <>{unit.data ? adminUnitLabel(unit.data, i18n.language) : code}</>;
}

/** Minimum-wage region resolved through the Legal Parameter Service (BR-CORE-006); never stored in Core. */
export function MinWageRegionTag({ data, error }: { data?: MinWageRegion; error?: unknown }) {
  const { t } = useTranslation(EMPLOYER_NS);
  if (error) {
    return (
      <Tooltip title={isProblem(error, 'legal-parameters-unavailable') ? t('minWage.unavailable') : undefined}>
        <Typography.Text type="secondary">{t('minWage.unknown')}</Typography.Text>
      </Tooltip>
    );
  }
  if (!data) return null;
  if (!data.region) return <Typography.Text type="secondary">{t('minWage.unknown')}</Typography.Text>;
  return (
    <Space size={4}>
      <Tag color="blue">{t('minWage.regionValue', { region: data.region })}</Tag>
      {data.overridden ? <Tag color="orange">{t('minWage.overridden')}</Tag> : null}
      {data.packVersion != null ? (
        <Typography.Text type="secondary">{t('minWage.packVersion', { version: data.packVersion })}</Typography.Text>
      ) : null}
    </Space>
  );
}

export const legalEntityLabel = (le: LegalEntity) =>
  le.current?.shortName || le.current?.legalNameVi ? `${le.code} – ${le.current?.shortName || le.current?.legalNameVi}` : le.code;

/** Legal-entity picker over the entities visible to the caller on `asOf`. */
export function LegalEntitySelect({
  value,
  onChange,
  asOf,
  id,
  allowClear,
  placeholder,
  disabled,
  style,
}: {
  value?: UUID | null;
  onChange?: (id: UUID | undefined) => void;
  asOf?: ISODate;
  id?: string;
  allowClear?: boolean;
  placeholder?: string;
  disabled?: boolean;
  style?: CSSProperties;
}) {
  const { t } = useTranslation(EMPLOYER_NS);
  const query = useLegalEntities(asOf);
  return (
    <Select
      id={id}
      showSearch
      optionFilterProp="label"
      allowClear={allowClear}
      disabled={disabled}
      style={style}
      aria-label={placeholder ?? t('common.selectLegalEntity')}
      placeholder={placeholder ?? t('common.selectLegalEntity')}
      loading={query.isLoading}
      value={value ?? undefined}
      onChange={(v) => onChange?.(v ?? undefined)}
      options={(query.data ?? [])
        .filter((le) => le.status !== 'DISSOLVED' || le.id === value)
        .map((le) => ({ value: le.id, label: legalEntityLabel(le) }))}
    />
  );
}

/** True when the version starts after today: only such planned versions can be removed (backend rule). */
export const isPlanned = (validFrom: string) => validFrom > today();

/**
 * Planned (not yet effective) versions with a "remove" action each. Shown in the history and where a close was
 * refused with `future-versions-exist`; removing one extends the version before it (backend).
 */
export function PlannedVersionList<T extends { versionId: string; validFrom: string; validTo?: string | null }>({
  versions,
  describe,
  onRemove,
  removing,
  canRemove,
}: {
  versions: T[] | undefined;
  describe: (v: T) => ReactNode;
  onRemove: (v: T) => Promise<unknown>;
  removing?: boolean;
  canRemove: boolean;
}) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const planned = (versions ?? []).filter((v) => isPlanned(v.validFrom));
  if (planned.length === 0) return null;
  return (
    <div>
      <Typography.Text strong>{te('planned.title')}</Typography.Text>
      <ul style={{ paddingLeft: 16, margin: '8px 0 0' }}>
        {planned.map((v) => (
          <li key={v.versionId} style={{ marginBottom: 4 }}>
            <Space wrap>
              <span>{formatValidity(v.validFrom, v.validTo, t('common.openEnded'))}</span>
              <span>{describe(v)}</span>
              {canRemove ? (
                <ConfirmButton
                  size="small"
                  danger
                  title={te('planned.removeConfirm', { date: formatDate(v.validFrom) })}
                  loading={removing}
                  onConfirm={() => onRemove(v)}
                >
                  {te('planned.remove')}
                </ConfirmButton>
              ) : null}
            </Space>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * "Close from a date" dialog shared by locations and org units. Mounted only while open; `children` is shown under
 * the problem (e.g. the planned versions to remove after `future-versions-exist`).
 */
export function CloseFromDateModal({
  title,
  hint,
  okText,
  error,
  pending,
  onSubmit,
  onCancel,
  children,
}: {
  title: ReactNode;
  hint: ReactNode;
  okText: string;
  error: unknown;
  pending: boolean;
  onSubmit: (effectiveFrom: string) => Promise<unknown>;
  onCancel: () => void;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const [form] = Form.useForm<{ effectiveFrom: Dayjs }>();
  const submit = async () => {
    let values: { effectiveFrom: Dayjs };
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await onSubmit(values.effectiveFrom.format(ISO_DATE));
    } catch {
      // shown by the ProblemAlert below
    }
  };
  return (
    <Modal
      open
      title={title}
      okText={okText}
      okButtonProps={{ danger: true, loading: pending }}
      cancelText={t('action.cancel')}
      onOk={() => void submit()}
      onCancel={onCancel}
    >
      <Typography.Paragraph type="secondary">{hint}</Typography.Paragraph>
      {error ? <ProblemAlert error={error} style={{ marginBottom: 12 }} /> : null}
      {children}
      <Form form={form} layout="vertical" initialValues={{ effectiveFrom: dayjs() }}>
        <Form.Item
          name="effectiveFrom"
          label={te('common.effectiveFrom')}
          rules={[{ required: true, message: te('common.effectiveFromRequired') }]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
