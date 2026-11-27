import { useEffect, useState } from 'react';
import { Alert, Button, Card, DatePicker, Empty, Form, Input, InputNumber, Space, Tabs, Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { AsOfPicker, ConfirmButton, DataTable, FormDrawer, PageHeader, useFeedback } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE, formatDate, formatDateTime, today } from '@/shared/format';
import { EmployeeSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import { isAdjustOutcome, type BalanceView, type LedgerRow, type LeaveRequestRow, type SettlementLine } from './api';
import { useAdjustBalance, useBalances, useEmploymentLeaveRequests, useFinaliseSettlement, useLedger, useSettlement } from './queries';
import { applyFieldErrors, formatQty, LeaveTypeName, LeaveTypeSelect, NS, RequestStatusTag, useEnumLabel } from './components';
import { leaveDateRange } from './LeaveRequestsPage';
import { NewLeaveRequestDrawer } from './NewLeaveRequestDrawer';

export interface BalancesSearch {
  employmentId?: UUID;
  asOf?: string;
  tab?: string;
}

const signed = (n: number) => <Typography.Text type={n < 0 ? 'danger' : undefined}>{formatQty(n)}</Typography.Text>;

/**
 * Leave balances of one employee (BR-ATT-067/068): balances per type × leave year, the ledger, the employee's requests,
 * a manual adjustment and the termination leave settlement (BR-ATT-065). The employee comes from `?employmentId=`.
 */
export function LeaveBalancesPage({ search, onChange }: { search: BalancesSearch; onChange: (next: BalancesSearch) => void }) {
  const { t } = useTranslation(NS);
  const { employmentId } = search;
  const asOf = search.asOf ?? today();
  const tab = search.tab ?? 'balances';
  const [adjusting, setAdjusting] = useState(false);
  const [requesting, setRequesting] = useState(false);
  const canSeeRequests = usePermission(P.LEAVE_REQUEST_VIEW);

  return (
    <div>
      <PageHeader
        title={t('balances.title')}
        subTitle={t('balances.intro')}
        extra={
          employmentId ? (
            <>
              <PermissionGate anyOf={P.LEAVE_REQUEST_CREATE}>
                <Button onClick={() => setRequesting(true)}>{t('requests.new')}</Button>
              </PermissionGate>
              <PermissionGate anyOf={P.LEAVE_BALANCE_ADJUST}>
                <Button type="primary" onClick={() => setAdjusting(true)}>
                  {t('balances.adjust')}
                </Button>
              </PermissionGate>
            </>
          ) : null
        }
      >
        <Space wrap>
          <EmployeeSelect
            aria-label={t('field.employee')}
            style={{ width: 360 }}
            value={employmentId}
            onChange={(id) => onChange({ ...search, employmentId: id ?? undefined })}
          />
          <AsOfPicker value={asOf} onChange={(d) => onChange({ ...search, asOf: d })} />
        </Space>
      </PageHeader>
      {!employmentId ? (
        <Empty description={t('balances.selectEmployee')} />
      ) : (
        <Tabs
          activeKey={tab}
          onChange={(k) => onChange({ ...search, tab: k })}
          destroyOnHidden
          items={[
            { key: 'balances', label: t('balances.tabBalances'), children: <BalancesTab employmentId={employmentId} asOf={asOf} /> },
            { key: 'ledger', label: t('balances.tabLedger'), children: <LedgerTab employmentId={employmentId} /> },
            ...(canSeeRequests
              ? [{ key: 'requests', label: t('balances.tabRequests'), children: <RequestsTab employmentId={employmentId} asOf={asOf} /> }]
              : []),
            { key: 'settlement', label: t('balances.tabSettlement'), children: <SettlementTab employmentId={employmentId} /> },
          ]}
        />
      )}
      {employmentId ? (
        <>
          <AdjustDrawer employmentId={employmentId} open={adjusting} onClose={() => setAdjusting(false)} />
          <NewLeaveRequestDrawer employmentId={employmentId} open={requesting} onClose={() => setRequesting(false)} />
        </>
      ) : null}
    </div>
  );
}

function BalancesTab({ employmentId, asOf }: { employmentId: UUID; asOf: string }) {
  const { t } = useTranslation(NS);
  const q = useBalances(employmentId, asOf);
  return (
    <DataTable<BalanceView>
      rowKey={(r) => `${r.leaveTypeId}-${r.leaveYear}`}
      data={q.data}
      isLoading={q.isLoading}
      error={q.error}
      columns={[
        { title: t('field.leaveType'), key: 'type', render: (_, r) => <LeaveTypeName id={r.leaveTypeId} code={r.leaveTypeCode} /> },
        {
          title: t('field.leaveYear'),
          key: 'year',
          render: (_, r) => (
            <span title={`${formatDate(r.yearStart)} – ${formatDate(r.yearEnd)}`}>
              {r.leaveYear}
            </span>
          ),
        },
        ...(['opening', 'accrued', 'carried', 'taken', 'adjusted', 'expired', 'encashed', 'balance', 'pending'] as const).map((k) => ({
          title: t(`balances.col.${k}`),
          key: k,
          align: 'right' as const,
          render: (_: unknown, r: BalanceView) => signed(r[k]),
        })),
        {
          title: t('balances.col.available'),
          key: 'available',
          align: 'right',
          render: (_, r) => <Typography.Text strong type={r.available < 0 ? 'danger' : undefined}>{formatQty(r.available)}</Typography.Text>,
        },
      ]}
    />
  );
}

function LedgerTab({ employmentId }: { employmentId: UUID }) {
  const { t } = useTranslation(NS);
  const label = useEnumLabel();
  const [leaveTypeId, setLeaveTypeId] = useState<UUID | undefined>();
  const [leaveYear, setLeaveYear] = useState<number | undefined>();
  const q = useLedger(employmentId, leaveTypeId, leaveYear);
  return (
    <>
      <Space wrap style={{ marginBottom: 12 }}>
        <LeaveTypeSelect
          aria-label={t('field.leaveType')}
          placeholder={t('common.allLeaveTypes')}
          style={{ width: 280 }}
          value={leaveTypeId}
          onChange={setLeaveTypeId}
        />
        <InputNumber
          aria-label={t('field.leaveYear')}
          placeholder={t('field.leaveYear')}
          min={2000}
          max={2100}
          value={leaveYear}
          onChange={(v) => setLeaveYear(typeof v === 'number' ? v : undefined)}
        />
      </Space>
      <DataTable<LedgerRow>
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          { title: t('field.txnDate'), dataIndex: 'txnDate', render: (v: string) => formatDate(v) },
          { title: t('field.leaveType'), key: 'type', render: (_, r) => <LeaveTypeName id={r.leaveTypeId} code={r.leaveTypeCode} /> },
          { title: t('field.leaveYear'), dataIndex: 'leaveYear' },
          { title: t('field.txnType'), dataIndex: 'txnType', render: (v: string) => <Tag>{label('txn', v)}</Tag> },
          {
            title: t('field.quantity'),
            key: 'qty',
            align: 'right',
            render: (_, r) => (
              <>
                {signed(r.quantity)} {label('unit', r.unit)}
              </>
            ),
          },
          { title: t('field.expiresOn'), dataIndex: 'expiresOn', render: (v: string | null) => formatDate(v) },
          {
            title: t('field.reference'),
            key: 'ref',
            render: (_, r) =>
              r.leaveRequestId ? (
                <Link to="/attendance/leave-requests/$requestId" params={{ requestId: r.leaveRequestId }}>
                  {label('ref', r.refType ?? 'LEAVE_REQUEST')}
                </Link>
              ) : (
                label('ref', r.refType)
              ),
          },
          { title: t('field.reason'), dataIndex: 'reason' },
          { title: t('field.source'), dataIndex: 'source', render: (v: string) => label('source', v) },
          { title: t('field.recordedAt'), dataIndex: 'createdAt', render: (v: string) => formatDateTime(v) },
        ]}
      />
    </>
  );
}

function RequestsTab({ employmentId, asOf }: { employmentId: UUID; asOf: string }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [range, setRange] = useState<[string, string]>([
    dayjs(asOf).startOf('year').format(ISO_DATE),
    dayjs(asOf).endOf('year').format(ISO_DATE),
  ]);
  const q = useEmploymentLeaveRequests(employmentId, range[0], range[1]);
  return (
    <>
      <DatePicker.RangePicker
        aria-label={t('field.period')}
        style={{ marginBottom: 12 }}
        format={DATE_FORMAT}
        allowClear={false}
        value={[dayjs(range[0]), dayjs(range[1])]}
        onChange={(v) => v?.[0] && v[1] && setRange([v[0].format(ISO_DATE), v[1].format(ISO_DATE)])}
      />
      <DataTable<LeaveRequestRow>
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          { title: t('field.leaveType'), key: 'type', render: (_, r) => <LeaveTypeName id={r.leaveTypeId} code={r.leaveTypeCode} /> },
          {
            title: t('field.dates'),
            key: 'dates',
            render: (_, r) => (
              <Link to="/attendance/leave-requests/$requestId" params={{ requestId: r.id }}>
                {leaveDateRange(r)}
              </Link>
            ),
          },
          {
            title: t('field.duration'),
            key: 'dur',
            align: 'right',
            render: (_, r) =>
              r.requestUnit === 'HOUR'
                ? t('requests.hoursValue', { value: formatQty(r.durationHours) })
                : t('requests.daysValue', { value: formatQty(r.durationDays) }),
          },
          { title: tc('field.status'), dataIndex: 'status', render: (s: string) => <RequestStatusTag status={s} /> },
        ]}
      />
    </>
  );
}

function SettlementTab({ employmentId }: { employmentId: UUID }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const q = useSettlement(employmentId, true);
  const finalise = useFinaliseSettlement(employmentId);
  const s = q.data;
  const allFinal = !!s && s.lines.length > 0 && s.lines.every((l) => l.finalised);
  return (
    <Card>
      {s ? (
        <Alert
          type={s.lastWorkingDate ? 'info' : 'warning'}
          showIcon
          style={{ marginBottom: 12 }}
          message={
            s.lastWorkingDate ? t('settlement.atLwd', { date: formatDate(s.lastWorkingDate) }) : t('settlement.projection')
          }
        />
      ) : null}
      <DataTable<SettlementLine>
        rowKey={(r) => `${r.leaveTypeId}-${r.leaveYear}`}
        data={s?.lines}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          { title: t('field.leaveType'), key: 'type', render: (_, r) => <LeaveTypeName id={r.leaveTypeId} code={r.leaveTypeCode} /> },
          { title: t('field.leaveYear'), dataIndex: 'leaveYear' },
          { title: t('settlement.entitled'), key: 'e', align: 'right', render: (_, r) => formatQty(r.entitledToLastDay) },
          { title: t('settlement.taken'), key: 't', align: 'right', render: (_, r) => formatQty(r.taken) },
          { title: t('settlement.remaining'), key: 'r', align: 'right', render: (_, r) => signed(r.remaining) },
          { title: t('settlement.encash'), key: 'enc', align: 'right', render: (_, r) => formatQty(Math.max(r.remaining, 0)) },
          { title: t('settlement.recover'), key: 'rec', align: 'right', render: (_, r) => formatQty(Math.max(-r.remaining, 0)) },
          {
            title: t('settlement.finalised'),
            key: 'f',
            render: (_, r) => (r.finalised ? <Tag color="green">{t('settlement.finalisedYes')}</Tag> : <Tag>{t('settlement.finalisedNo')}</Tag>),
          },
        ]}
      />
      <PermissionGate anyOf={P.LEAVE_BALANCE_ADJUST}>
        <div style={{ marginTop: 12, display: 'flex', justifyContent: 'flex-end' }}>
          <ConfirmButton
            type="primary"
            disabled={!s?.lastWorkingDate || allFinal}
            loading={finalise.isPending}
            title={t('settlement.finaliseConfirm')}
            description={t('settlement.finaliseHint')}
            onConfirm={async () => {
              try {
                await finalise.mutateAsync();
                fb.done(t('settlement.finalisedDone'));
              } catch (e) {
                fb.failed(e);
              }
            }}
          >
            {t('settlement.finalise')}
          </ConfirmButton>
        </div>
      </PermissionGate>
    </Card>
  );
}

interface AdjustForm {
  leaveTypeId: UUID;
  leaveYear?: number | null;
  quantity: number;
  reason: string;
}

function AdjustDrawer({ employmentId, open, onClose }: { employmentId: UUID; open: boolean; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<AdjustForm>();
  const adjust = useAdjustBalance(employmentId);

  useEffect(() => {
    if (open) {
      form.resetFields();
      adjust.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, form]);

  const submit = async () => {
    let v: AdjustForm;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const res = await adjust.mutateAsync({ leaveTypeId: v.leaveTypeId, leaveYear: v.leaveYear ?? null, quantity: v.quantity, reason: v.reason });
      fb.done(isAdjustOutcome(res) && res.outcome === 'APPROVAL_REQUESTED' ? t('balances.adjustPending') : t('balances.adjusted'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const required = { required: true, message: tc('common.required') };
  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('balances.adjust')}
      onSubmit={() => void submit()}
      submitting={adjust.isPending}
      error={adjust.error}
      width={480}
    >
      <Alert type="info" showIcon message={t('balances.adjustHint')} style={{ marginBottom: 12 }} />
      <Form<AdjustForm> form={form} layout="vertical">
        <Form.Item name="leaveTypeId" label={t('field.leaveType')} rules={[required]}>
          <LeaveTypeSelect />
        </Form.Item>
        <Form.Item name="leaveYear" label={t('field.leaveYear')} extra={t('balances.leaveYearHint')}>
          <InputNumber min={2000} max={2100} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="quantity"
          label={t('field.quantity')}
          extra={t('balances.quantityHint')}
          rules={[required, { validator: (_, v) => (v === 0 ? Promise.reject(new Error(t('balances.quantityNonZero'))) : Promise.resolve()) }]}
        >
          <InputNumber step={0.5} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="reason" label={t('field.reason')} rules={[{ required: true, whitespace: true, message: t('balances.reasonRequired') }]}>
          <Input.TextArea rows={3} maxLength={1000} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
