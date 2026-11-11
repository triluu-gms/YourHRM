import { DatePicker, Space, Typography } from 'antd';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { DATE_FORMAT, ISO_DATE } from '@/shared/format';

/** "As of" date for effective-dated lists and trees (ADR-0024): ISO date in state, dd/MM/yyyy on screen. */
export function AsOfPicker({
  value,
  onChange,
  label,
}: {
  value: string;
  onChange: (iso: string) => void;
  label?: string;
}) {
  const { t } = useTranslation();
  return (
    <Space>
      <Typography.Text>{label ?? t('field.asOf')}</Typography.Text>
      <DatePicker
        allowClear={false}
        format={DATE_FORMAT}
        value={dayjs(value)}
        onChange={(d) => d && onChange(d.format(ISO_DATE))}
      />
    </Space>
  );
}
