import type { ReactNode } from 'react';
import { DatePicker, Select, Space, Tag, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PageHeader, ProblemAlert } from '@/shared/components';
import { ISO_DATE, formatDate, formatNumber } from '@/shared/format';
import { legalEntityLabel, useLegalEntities } from '../../common';
import { useEmploymentChoice, type EmploymentChoice } from './queries';
import { ESS_NS } from './i18n';

/** Server label language (`lang`). */
export function useLang(): string {
  const { i18n } = useTranslation();
  return i18n.language.startsWith('en') ? 'en' : 'vi';
}

export const iso = (d: Dayjs) => d.format(ISO_DATE);

export function qty(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : formatNumber(value);
}

/** Minutes → hours with up to 2 decimals. */
export const hours = (min: number | null | undefined) => (min == null ? '' : formatNumber(Math.round((min / 60) * 100) / 100));

/** Switcher between the caller's concurrent employments; shown only when there is more than one. */
export function EmploymentSwitcher({ choice }: { choice: EmploymentChoice }) {
  const { t } = useTranslation(ESS_NS);
  const { i18n } = useTranslation();
  const les = useLegalEntities();
  if (choice.employments.length < 2) return null;
  return (
    <Select<UUID>
      aria-label={t('common.employment')}
      style={{ minWidth: 320 }}
      value={choice.employmentId}
      onChange={choice.setEmploymentId}
      options={choice.employments.map((e) => {
        const le = legalEntityLabel(les.data?.find((l) => l.id === e.legalEntityId), i18n.language);
        return {
          value: e.employmentId,
          label: `${le || e.legalEntityId.slice(0, 8)} · ${t('common.since', { date: formatDate(e.hireDate) })}${
            e.activeToday ? '' : ` · ${t('common.inactive')}`
          }`,
        };
      })}
    />
  );
}

/** ESS page frame: title, the employment switcher and page controls, and the `/me/employments` problem if any. */
export function EssFrame({
  title,
  subTitle,
  extra,
  controls,
  children,
}: {
  title: ReactNode;
  subTitle?: ReactNode;
  extra?: ReactNode;
  controls?: ReactNode;
  children: ReactNode;
}) {
  const choice = useEmploymentChoice();
  return (
    <div>
      <PageHeader title={title} subTitle={subTitle} extra={extra}>
        <Space wrap>
          <EmploymentSwitcher choice={choice} />
          {controls}
        </Space>
      </PageHeader>
      {choice.error ? <ProblemAlert error={choice.error} style={{ marginBottom: 12 }} /> : null}
      {children}
    </div>
  );
}

/** Month picker; the pages query the month (≤ 31 days, under the 62-day limit). */
export function MonthPicker({ value, onChange }: { value: Dayjs; onChange: (d: Dayjs) => void }) {
  const { t } = useTranslation(ESS_NS);
  return (
    <DatePicker
      picker="month"
      format="MM/YYYY"
      allowClear={false}
      aria-label={t('common.month')}
      value={value}
      onChange={(d) => d && onChange(d)}
    />
  );
}

export function monthRange(month: Dayjs): { from: string; to: string } {
  return { from: iso(month.startOf('month')), to: iso(month.endOf('month')) };
}

export const thisMonth = () => dayjs().startOf('month');

/** Exceptions of a day as tags (red when they block the period close). */
export function ExceptionTags({ items }: { items: { code: string; label: string; blocking: boolean }[] }) {
  if (items.length === 0) return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <Space size={[4, 4]} wrap>
      {items.map((x) => (
        <Tag key={x.code} color={x.blocking ? 'red' : 'orange'}>
          {x.label}
        </Tag>
      ))}
    </Space>
  );
}
