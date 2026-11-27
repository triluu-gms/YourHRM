import { useState, type ReactNode } from 'react';
import { Alert, Checkbox, Form, Input, Modal, Space, Table, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { ApiProblem, type UUID } from '@/shared/api';
import { ProblemAlert, StatusTag } from '@/shared/components';
import { formatDate } from '@/shared/format';
import { legalEntityLabel, useLegalEntities } from '../../common';
import type { Checklist, ChecklistItem, CloseRequest } from './api';
import { PERIODS_NS } from './i18n';

export const NS = PERIODS_NS;

const COLOURS: Record<string, string> = {
  OPEN: 'blue',
  IN_REVIEW: 'gold',
  CLOSED: 'green',
  LOCKED: 'orange',
  PUBLISHED: 'green',
  WITHDRAWN: 'default',
  ACTIVE: 'orange',
  RELEASED: 'default',
  CONSUMED: 'purple',
  STAGED: 'gold',
  EMPTY: 'default',
};

/** Period / result / hold / import status with this area's labels. */
export function AreaStatusTag({ status }: { status: string | null | undefined }) {
  const { t } = useTranslation(NS);
  if (!status) return null;
  const label = t(`status.${status}`, { defaultValue: status });
  return COLOURS[status] ? <Tag color={COLOURS[status]}>{label}</Tag> : <StatusTag status={status} labels={{ [status]: label }} />;
}

export function LegalEntityName({ id }: { id: UUID | null | undefined }) {
  const { i18n } = useTranslation();
  const q = useLegalEntities();
  if (!id) return null;
  const le = q.data?.find((x) => x.id === id);
  return <>{le ? legalEntityLabel(le, i18n.language) : id}</>;
}

/** Ids without a lookup endpoint in `att` (pay groups, employments in lines): monospace, shortened, full on hover. */
export function ShortId({ id }: { id: UUID | null | undefined }) {
  if (!id) return null;
  return (
    <Typography.Text code title={id} copyable={{ text: id }}>
      {id.slice(0, 8)}
    </Typography.Text>
  );
}

export const range = (from?: string | null, to?: string | null) => (from ? `${formatDate(from)} – ${formatDate(to)}` : '');

/** Checklist items (BR-ATT-074); with `waived` / `onWaive` the waivable blocking items get a checkbox. */
export function ChecklistTable({
  items,
  loading,
  waived,
  onWaive,
}: {
  items: ChecklistItem[] | undefined;
  loading?: boolean;
  waived?: string[];
  onWaive?: (codes: string[]) => void;
}) {
  const { t } = useTranslation(NS);
  return (
    <Table<ChecklistItem>
      size="small"
      rowKey="code"
      loading={loading}
      pagination={false}
      dataSource={items ?? []}
      locale={{ emptyText: t('checklist.empty') }}
      columns={[
        {
          title: t('field.item'),
          dataIndex: 'code',
          render: (c: string) => (
            <Space direction="vertical" size={0}>
              <span>{t(`checklist.code.${c}`, { defaultValue: c })}</span>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {c}
              </Typography.Text>
            </Space>
          ),
        },
        { title: t('field.count'), dataIndex: 'count' },
        {
          title: t('checklist.blocking'),
          key: 'flags',
          render: (_: unknown, i: ChecklistItem) => (
            <Space size={4} wrap>
              {i.blocking ? <Tag color="red">{t('checklist.blocking')}</Tag> : null}
              {i.blocking ? <Tag>{i.waivable ? t('checklist.waivable') : t('checklist.notWaivable')}</Tag> : null}
            </Space>
          ),
        },
        ...(onWaive
          ? [
              {
                title: t('actions.waive'),
                key: 'waive',
                render: (_: unknown, i: ChecklistItem) =>
                  i.blocking && i.waivable ? (
                    <Checkbox
                      aria-label={`${t('actions.waive')} ${i.code}`}
                      checked={waived?.includes(i.code)}
                      onChange={(e) =>
                        onWaive(e.target.checked ? [...(waived ?? []), i.code] : (waived ?? []).filter((c) => c !== i.code))
                      }
                    />
                  ) : null,
              },
            ]
          : []),
      ]}
    />
  );
}

export function ChecklistSummary({ checklist }: { checklist: Checklist }) {
  const { t } = useTranslation(NS);
  return (
    <Alert
      type={checklist.canClose ? 'success' : 'warning'}
      showIcon
      style={{ marginBottom: 12 }}
      message={checklist.canClose ? t('checklist.canClose') : t('checklist.cannotClose')}
      description={t('checklist.dateRange', { from: formatDate(checklist.dateFrom), to: formatDate(checklist.dateTo) })}
    />
  );
}

/** Items of a 422 `close-blocked` (`problem.items`). */
export function blockedItemsOf(error: unknown): ChecklistItem[] | undefined {
  if (error instanceof ApiProblem && Array.isArray(error.problem.items)) return error.problem.items as ChecklistItem[];
  return undefined;
}

/**
 * Confirm dialog of a close / early close: the checklist with waiver checkboxes, the waiver reason (required as soon
 * as one item is waived) and the server's answer.
 */
export function CloseDialog({
  open,
  title,
  hint,
  okText,
  checklist,
  checklistLoading,
  onClose,
  submit,
  pending,
  error,
}: {
  open: boolean;
  title: ReactNode;
  hint: ReactNode;
  okText: ReactNode;
  checklist?: Checklist;
  checklistLoading?: boolean;
  onClose: () => void;
  submit: (body: CloseRequest) => Promise<unknown>;
  pending?: boolean;
  error?: unknown;
}) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [waived, setWaived] = useState<string[]>([]);
  const [form] = Form.useForm<{ waiveReason?: string }>();
  const items = blockedItemsOf(error) ?? checklist?.items;

  const onOk = async () => {
    const v = await form.validateFields().catch(() => null);
    if (!v) return;
    try {
      await submit({ waive: waived, waiveReason: waived.length > 0 ? (v.waiveReason ?? null) : null });
      setWaived([]);
      onClose();
    } catch {
      // shown below
    }
  };

  return (
    <Modal
      open={open}
      title={title}
      okText={okText}
      cancelText={tc('action.cancel')}
      confirmLoading={pending}
      onOk={() => void onOk()}
      onCancel={onClose}
      width={720}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">{hint}</Typography.Paragraph>
      {error ? <ProblemAlert error={error} style={{ marginBottom: 12 }} /> : null}
      {checklist ? <ChecklistSummary checklist={checklist} /> : null}
      <ChecklistTable items={items} loading={checklistLoading} waived={waived} onWaive={setWaived} />
      <Form form={form} layout="vertical" preserve={false} style={{ marginTop: 12 }}>
        <Form.Item
          name="waiveReason"
          label={t('actions.waiveReason')}
          extra={t('actions.waiveReasonHint')}
          rules={waived.length > 0 ? [{ required: true, whitespace: true, message: tc('common.required') }] : []}
        >
          <Input.TextArea rows={2} maxLength={500} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** Confirm dialog asking for a mandatory reason (reopen). */
export function ReasonDialog({
  open,
  title,
  hint,
  label,
  okText,
  onClose,
  submit,
  pending,
  error,
}: {
  open: boolean;
  title: ReactNode;
  hint?: ReactNode;
  label: ReactNode;
  okText: ReactNode;
  onClose: () => void;
  submit: (reason: string) => Promise<unknown>;
  pending?: boolean;
  error?: unknown;
}) {
  const { t: tc } = useTranslation();
  const [form] = Form.useForm<{ reason: string }>();
  const onOk = async () => {
    const v = await form.validateFields().catch(() => null);
    if (!v) return;
    try {
      await submit(v.reason.trim());
      onClose();
    } catch {
      // shown below
    }
  };
  return (
    <Modal
      open={open}
      title={title}
      okText={okText}
      okButtonProps={{ danger: true }}
      cancelText={tc('action.cancel')}
      confirmLoading={pending}
      onOk={() => void onOk()}
      onCancel={onClose}
      destroyOnHidden
    >
      {hint ? <Typography.Paragraph type="secondary">{hint}</Typography.Paragraph> : null}
      {error ? <ProblemAlert error={error} style={{ marginBottom: 12 }} /> : null}
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item name="reason" label={label} rules={[{ required: true, whitespace: true, message: tc('common.required') }]}>
          <Input.TextArea rows={3} maxLength={500} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
