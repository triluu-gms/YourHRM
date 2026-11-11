import { Alert, DatePicker, Form, InputNumber, Select, AutoComplete } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { FormDrawer, useFeedback } from '@/shared/components';
import { DATE_FORMAT } from '@/shared/format';
import { applyFieldErrors, LegalEntitySelect, toIsoDate } from '../../common';
import { STATUTORY_REPORT_TYPES, type StatutoryReportGeneration, type StatutoryReportType } from './api';
import { useGenerateReport } from './queries';
import './i18n';

interface FormValues {
  legalEntityId: UUID;
  reportType: StatutoryReportType;
  year?: number;
  period?: string;
  month?: Dayjs | null;
  asOf?: Dayjs | null;
}

/** Period codes usual in the legal pack (`report.labour_change_6m.periods`); free text is allowed too. */
const SIX_MONTH_PERIODS = ['JUNE', 'DECEMBER'];

/**
 * Generates (or regenerates) a statutory report into the register (POST /people/statutory-reports):
 * 6-monthly → year + period, monthly → month, labour book → as-of date.
 */
export function GenerateReportDrawer({
  open,
  onClose,
  legalEntityId,
  onGenerated,
}: {
  open: boolean;
  onClose: () => void;
  legalEntityId?: UUID;
  onGenerated?: (g: StatutoryReportGeneration) => void;
}) {
  const { t } = useTranslation('people-reports');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const generate = useGenerateReport();
  const type = Form.useWatch('reportType', form);

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const g = await generate.mutateAsync({
        legalEntityId: v.legalEntityId,
        reportType: v.reportType,
        year:
          v.reportType === 'LABOUR_FLUCTUATION_MONTHLY'
            ? (v.month?.year() ?? null)
            : v.reportType === 'LABOUR_CHANGE_6M'
              ? v.year
              : null,
        period: v.reportType === 'LABOUR_CHANGE_6M' ? (v.period?.trim() ?? null) : null,
        month: v.reportType === 'LABOUR_FLUCTUATION_MONTHLY' && v.month ? v.month.month() + 1 : null,
        asOf: v.reportType === 'LABOUR_BOOK_EXPORT' ? toIsoDate(v.asOf) : null,
      });
      fb.done(t('statutory.generated'));
      onGenerated?.(g);
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('statutory.generate')}
      onSubmit={() => void submit()}
      submitting={generate.isPending}
      error={generate.error}
      submitLabel={t('statutory.generate')}
      width={480}
    >
      <Alert type="info" showIcon message={t('statutory.regenerateHint')} style={{ marginBottom: 16 }} />
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={{ legalEntityId, reportType: 'LABOUR_CHANGE_6M', year: dayjs().year() }}
      >
        <Form.Item
          name="legalEntityId"
          label={t('common.legalEntity')}
          rules={[{ required: true, message: t('common.legalEntityRequired') }]}
        >
          <LegalEntitySelect />
        </Form.Item>
        <Form.Item
          name="reportType"
          label={t('statutory.type')}
          rules={[{ required: true, message: t('statutory.typeRequired') }]}
        >
          <Select
            options={STATUTORY_REPORT_TYPES.map((x) => ({ value: x, label: t(`statutory.types.${x}`) }))}
          />
        </Form.Item>
        {type === 'LABOUR_CHANGE_6M' ? (
          <>
            <Form.Item
              name="year"
              label={t('common.year')}
              rules={[{ required: true, message: t('statutory.yearRequired') }]}
            >
              <InputNumber min={2000} max={2100} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="period"
              label={t('common.period')}
              extra={t('statutory.periodHint')}
              rules={[
                { required: true, whitespace: true, message: t('statutory.periodRequired') },
                { max: 30 },
              ]}
            >
              <AutoComplete options={SIX_MONTH_PERIODS.map((p) => ({ value: p }))} />
            </Form.Item>
          </>
        ) : null}
        {type === 'LABOUR_FLUCTUATION_MONTHLY' ? (
          <Form.Item
            name="month"
            label={t('common.month')}
            rules={[{ required: true, message: t('statutory.monthRequired') }]}
          >
            <DatePicker picker="month" format="MM/YYYY" style={{ width: '100%' }} />
          </Form.Item>
        ) : null}
        {type === 'LABOUR_BOOK_EXPORT' ? (
          <Form.Item name="asOf" label={t('common.asOf')}>
            <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
          </Form.Item>
        ) : null}
      </Form>
    </FormDrawer>
  );
}
