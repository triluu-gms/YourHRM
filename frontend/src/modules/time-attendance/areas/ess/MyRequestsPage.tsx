import { useState } from 'react';
import { DatePicker, Select, Tag } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { DataTable, StatusTag } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime } from '@/shared/format';
import type { MyRequest } from './api';
import { useEmploymentChoice, useMyRequests } from './queries';
import { EssFrame, iso, qty, useLang } from './shared';
import { ESS_NS } from './i18n';
import './i18n';

const KINDS = ['LEAVE', 'OVERTIME', 'CORRECTION'] as const;
const KIND_COLOURS: Record<string, string> = { LEAVE: 'blue', OVERTIME: 'purple', CORRECTION: 'cyan' };

/** "My requests": the caller's leave, overtime and timesheet correction requests overlapping a range (at most 62 days). */
export function MyRequestsPage() {
  const { t } = useTranslation(ESS_NS);
  const lang = useLang();
  const choice = useEmploymentChoice();
  const [range, setRange] = useState<[Dayjs, Dayjs]>(() => [dayjs().subtract(30, 'day'), dayjs().add(30, 'day')]);
  const [kind, setKind] = useState<string | undefined>();
  const q = useMyRequests({ employmentId: choice.employmentId, lang, from: iso(range[0]), to: iso(range[1]) }, choice.ready);
  const rows = (q.data ?? []).filter((r) => !kind || r.kind === kind);

  return (
    <EssFrame
      title={t('requests.title')}
      subTitle={t('requests.intro')}
      controls={
        <>
          <DatePicker.RangePicker
            aria-label={t('requests.range')}
            format={DATE_FORMAT}
            allowClear={false}
            value={range}
            onChange={(v) => {
              if (v?.[0] && v[1]) setRange([v[0], v[1]]);
            }}
          />
          <Select<string>
            allowClear
            aria-label={t('requests.kind')}
            placeholder={t('requests.allKinds')}
            style={{ width: 200 }}
            value={kind}
            onChange={setKind}
            options={KINDS.map((k) => ({ value: k, label: t(`requests.kinds.${k}`) }))}
          />
        </>
      }
    >
      <DataTable<MyRequest>
        size="small"
        rowKey={(r) => `${r.kind}:${r.id}`}
        data={rows}
        isLoading={q.isFetching}
        error={q.error}
        columns={[
          { title: t('requests.kind'), key: 'kind', render: (_, r) => <Tag color={KIND_COLOURS[r.kind]}>{r.kindLabel || r.kind}</Tag> },
          {
            title: t('requests.dates'),
            key: 'dates',
            render: (_, r) => (r.fromDate === r.toDate ? formatDate(r.fromDate) : `${formatDate(r.fromDate)} – ${formatDate(r.toDate)}`),
          },
          { title: t('requests.subject'), key: 'subject', render: (_, r) => r.subjectLabel ?? r.subject ?? '—' },
          {
            title: t('requests.quantity'),
            key: 'q',
            align: 'right',
            render: (_, r) =>
              r.quantity == null ? '' : `${qty(r.quantity)} ${r.unit ? t(`leave.units.${r.unit}`, { defaultValue: r.unit }) : ''}`,
          },
          {
            title: t('timesheet.status'),
            key: 'status',
            render: (_, r) => <StatusTag status={r.status} labels={{ [r.status]: r.statusLabel }} />,
          },
          { title: t('requests.decidedAt'), dataIndex: 'decidedAt', render: formatDateTime },
        ]}
      />
    </EssFrame>
  );
}
