import { useState } from 'react';
import { Card, DatePicker, Form, Input, Select, Tag } from 'antd';
import { Link } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, PageHeader } from '@/shared/components';
import { DATE_FORMAT, formatDate, ISO_DATE } from '@/shared/format';
import { ATT_NS, LegalEntitySelect } from '../../common';
import { PERIOD_STATUSES, type PeriodFilter, type PeriodStatus, type PeriodView } from './api';
import { usePeriods } from './queries';
import { AreaStatusTag, LegalEntityName, NS, range, ShortId } from './components';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** `/attendance/periods`: periods per legal entity × pay group with filters (BRD §4.8). */
export function PeriodsListPage() {
  const { t } = useTranslation(NS);
  const { t: ta } = useTranslation(ATT_NS);
  const { t: tc } = useTranslation();
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [status, setStatus] = useState<PeriodStatus | undefined>();
  const [payGroupId, setPayGroupId] = useState('');
  const [dates, setDates] = useState<[Dayjs, Dayjs] | null>(null);

  const filter: PeriodFilter = {
    legalEntityId,
    status,
    payGroupId: UUID_RE.test(payGroupId.trim()) ? payGroupId.trim() : undefined,
    from: dates?.[0].format(ISO_DATE),
    to: dates?.[1].format(ISO_DATE),
  };
  const q = usePeriods(filter);

  return (
    <div>
      <PageHeader title={t('list.title')} subTitle={t('list.subTitle')} />
      <Card size="small" style={{ marginBottom: 16 }}>
        <Form layout="inline" style={{ rowGap: 8 }}>
          <Form.Item label={ta('common.legalEntity')}>
            <LegalEntitySelect style={{ minWidth: 260 }} aria-label={ta('common.legalEntity')} value={legalEntityId} onChange={(v) => setLegalEntityId(v ?? undefined)} />
          </Form.Item>
          <Form.Item label={t('field.status')}>
            <Select<PeriodStatus>
              allowClear
              style={{ minWidth: 160 }}
              aria-label={t('field.status')}
              value={status}
              onChange={(v) => setStatus(v ?? undefined)}
              options={PERIOD_STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))}
            />
          </Form.Item>
          <Form.Item label={t('field.payGroup')}>
            <Input
              allowClear
              style={{ width: 300 }}
              placeholder={t('list.payGroupId')}
              value={payGroupId}
              onChange={(e) => setPayGroupId(e.target.value)}
              status={payGroupId.trim() && !UUID_RE.test(payGroupId.trim()) ? 'error' : undefined}
            />
          </Form.Item>
          <Form.Item label={t('list.range')}>
            <DatePicker.RangePicker
              format={DATE_FORMAT}
              value={dates}
              onChange={(v) => setDates(v?.[0] && v[1] ? [v[0], v[1]] : null)}
            />
          </Form.Item>
        </Form>
      </Card>
      <DataTable<PeriodView>
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          {
            title: t('field.period'),
            key: 'period',
            render: (_: unknown, p: PeriodView) => (
              <Link to="/attendance/periods/$periodId" params={{ periodId: p.id }}>
                {range(p.periodStart, p.periodEnd)}
              </Link>
            ),
          },
          { title: t('field.legalEntity'), dataIndex: 'legalEntityId', render: (id: UUID) => <LegalEntityName id={id} /> },
          { title: t('field.payGroup'), dataIndex: 'payGroupId', render: (id: UUID) => <ShortId id={id} /> },
          {
            title: t('field.status'),
            dataIndex: 'status',
            render: (s: string, p: PeriodView) => (
              <>
                <AreaStatusTag status={s} />
                {p.activeHold ? <Tag color="orange">{t('status.ACTIVE')}</Tag> : null}
              </>
            ),
          },
          { title: t('field.currentVersion'), dataIndex: 'currentVersion' },
          { title: t('field.lockedVersion'), dataIndex: 'lockedVersion' },
          { title: t('field.closeDeadline'), dataIndex: 'closeDeadline', render: (d: string) => formatDate(d) },
          {
            title: t('field.stdDayMethod'),
            dataIndex: 'stdDayMethod',
            render: (m: string | null) => (m ? t(`stdMethod.${m}`, { defaultValue: m }) : <Tag color="red">{t('checklist.code.STD_METHOD_MISSING')}</Tag>),
          },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_: unknown, p: PeriodView) => (
              <Link to="/attendance/periods/$periodId" params={{ periodId: p.id }}>
                {t('list.open')}
              </Link>
            ),
          },
        ]}
      />
    </div>
  );
}
