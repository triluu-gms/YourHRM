import { useState } from 'react';
import { Button, DatePicker, InputNumber, Select, Space, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import type { MyBalance, MyLedgerEntry } from './api';
import { useEmploymentChoice, useMyBalances, useMyLedger } from './queries';
import { RequestLeaveDrawer } from './RequestLeaveDrawer';
import { EssFrame, iso, qty, useLang } from './shared';
import { ESS_NS } from './i18n';
import './i18n';

/** "My leave": balances per leave type as of a date and the ledger movements; request leave when permitted. */
export function MyLeavePage() {
  const { t } = useTranslation(ESS_NS);
  const lang = useLang();
  const choice = useEmploymentChoice();
  const [asOf, setAsOf] = useState<Dayjs>(() => dayjs());
  const [leaveTypeId, setLeaveTypeId] = useState<UUID | undefined>();
  const [leaveYear, setLeaveYear] = useState<number | undefined>();
  const [requesting, setRequesting] = useState(false);
  const balances = useMyBalances({ employmentId: choice.employmentId, lang, asOf: iso(asOf) }, choice.ready);
  const ledger = useMyLedger({ employmentId: choice.employmentId, lang, leaveTypeId, leaveYear }, choice.ready);
  const types = [...new Map((balances.data ?? []).map((b) => [b.leaveTypeId, b])).values()];
  const requestEmployment = choice.employmentId ?? choice.employments[0]?.employmentId;

  return (
    <EssFrame
      title={t('leave.title')}
      subTitle={t('leave.intro')}
      extra={
        <PermissionGate anyOf={P.LEAVE_REQUEST_CREATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setRequesting(true)}>
            {t('request.open')}
          </Button>
        </PermissionGate>
      }
      controls={
        <DatePicker
          aria-label={t('leave.asOf')}
          format={DATE_FORMAT}
          allowClear={false}
          value={asOf}
          onChange={(d) => d && setAsOf(d)}
        />
      }
    >
      <Typography.Title level={5}>{t('leave.balances')}</Typography.Title>
      <DataTable<MyBalance>
        size="small"
        rowKey={(b) => `${b.leaveTypeId}:${b.leaveYear}`}
        data={balances.data}
        isLoading={balances.isFetching}
        error={balances.error}
        columns={[
          { title: t('leave.type'), key: 'type', render: (_, b) => `${b.leaveTypeCode} – ${b.leaveTypeLabel}` },
          {
            title: t('leave.year'),
            key: 'year',
            render: (_, b) => (
              <>
                {b.leaveYear}{' '}
                <Typography.Text type="secondary">
                  ({formatDate(b.yearStart)} – {formatDate(b.yearEnd)})
                </Typography.Text>
              </>
            ),
          },
          ...(['opening', 'carried', 'accrued', 'taken', 'adjusted', 'expired', 'encashed', 'balance', 'pending'] as const).map(
            (k) => ({
              key: k,
              title: t(`leave.columns.${k}`),
              align: 'right' as const,
              render: (_: unknown, b: MyBalance) => qty(b[k]),
            }),
          ),
          {
            key: 'available',
            title: t('leave.columns.available'),
            align: 'right',
            render: (_, b) => (
              <Typography.Text strong type={b.available < 0 ? 'danger' : undefined}>
                {qty(b.available)}
              </Typography.Text>
            ),
          },
        ]}
      />

      <Space style={{ marginTop: 24, marginBottom: 8 }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {t('leave.ledger')}
        </Typography.Title>
        <Select<UUID>
          allowClear
          aria-label={t('leave.type')}
          placeholder={t('leave.allTypes')}
          style={{ width: 240 }}
          value={leaveTypeId}
          onChange={setLeaveTypeId}
          options={types.map((b) => ({ value: b.leaveTypeId, label: `${b.leaveTypeCode} – ${b.leaveTypeLabel}` }))}
        />
        <InputNumber<number>
          aria-label={t('leave.year')}
          placeholder={t('leave.year')}
          min={2000}
          max={2100}
          value={leaveYear}
          onChange={(v) => setLeaveYear(v ?? undefined)}
        />
      </Space>
      <DataTable<MyLedgerEntry>
        size="small"
        data={ledger.data}
        isLoading={ledger.isFetching}
        error={ledger.error}
        columns={[
          { title: t('leave.txnDate'), dataIndex: 'txnDate', render: formatDate },
          { title: t('leave.type'), key: 'type', render: (_, e) => `${e.leaveTypeCode} – ${e.leaveTypeLabel}` },
          { title: t('leave.year'), dataIndex: 'leaveYear' },
          { title: t('leave.txnType'), dataIndex: 'txnType', render: (v: string) => t(`leave.txnTypes.${v}`, { defaultValue: v }) },
          {
            title: t('leave.quantity'),
            key: 'q',
            align: 'right',
            render: (_, e) => (
              <Typography.Text type={e.quantity < 0 ? 'danger' : 'success'}>
                {e.quantity > 0 ? '+' : ''}
                {qty(e.quantity)} {t(`leave.units.${e.unit}`, { defaultValue: e.unit })}
              </Typography.Text>
            ),
          },
          { title: t('leave.expiresOn'), dataIndex: 'expiresOn', render: formatDate },
          { title: t('leave.reason'), dataIndex: 'reason' },
        ]}
      />

      <RequestLeaveDrawer
        open={requesting}
        onClose={() => setRequesting(false)}
        employmentId={requestEmployment}
        balances={balances.data ?? []}
      />
    </EssFrame>
  );
}
