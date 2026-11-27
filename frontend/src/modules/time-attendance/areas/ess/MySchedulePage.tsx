import { useState } from 'react';
import { Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { DataTable } from '@/shared/components';
import { formatDate, localizedName } from '@/shared/format';
import type { PlannedDay } from './api';
import { useEmploymentChoice, useMySchedule } from './queries';
import { EssFrame, MonthPicker, monthRange, qty, thisMonth } from './shared';
import { ESS_NS } from './i18n';
import './i18n';

const KIND_COLOURS: Record<string, string> = { WORK: 'blue', REST: 'default', OFF: 'default' };

/** "My schedule": the planned days of a month (shift, rest days, holidays and substitute days). */
export function MySchedulePage() {
  const { t, i18n } = useTranslation(ESS_NS);
  const choice = useEmploymentChoice();
  const [month, setMonth] = useState(thisMonth);
  const q = useMySchedule({ employmentId: choice.employmentId, ...monthRange(month) }, choice.ready);

  return (
    <EssFrame title={t('schedule.title')} subTitle={t('schedule.intro')} controls={<MonthPicker value={month} onChange={setMonth} />}>
      <DataTable<PlannedDay>
        size="small"
        rowKey="date"
        data={q.data}
        isLoading={q.isFetching}
        error={q.error}
        columns={[
          {
            title: t('timesheet.date'),
            dataIndex: 'date',
            render: (v: string) => (
              <>
                {formatDate(v)} <Typography.Text type="secondary">({dayjs(v).format('dd')})</Typography.Text>
              </>
            ),
          },
          {
            title: t('schedule.dayKind'),
            key: 'kind',
            render: (_, d) =>
              !d.scheduled ? (
                <Tag color="orange">{t('schedule.unscheduled')}</Tag>
              ) : (
                <Tag color={KIND_COLOURS[d.dayKind ?? ''] ?? 'default'}>
                  {t(`schedule.kinds.${d.dayKind}`, { defaultValue: d.dayKind ?? '' })}
                </Tag>
              ),
          },
          { title: t('schedule.shift'), dataIndex: 'shiftCode', render: (v: string | null) => v ?? '—' },
          { title: t('schedule.stdHours'), dataIndex: 'stdHours', align: 'right', render: qty },
          { title: t('timesheet.dayValue'), dataIndex: 'dayValue', align: 'right', render: qty },
          {
            title: t('schedule.holiday'),
            key: 'holiday',
            render: (_, d) =>
              d.holiday ? (
                <Tag color="red">{localizedName(d.holiday, i18n.language)}</Tag>
              ) : d.substituteForDate ? (
                <Tag color="volcano">{t('schedule.substituteFor', { date: formatDate(d.substituteForDate) })}</Tag>
              ) : (
                ''
              ),
          },
          { title: t('schedule.pattern'), dataIndex: 'patternCode', render: (v: string | null) => v ?? '—' },
        ]}
      />
    </EssFrame>
  );
}
