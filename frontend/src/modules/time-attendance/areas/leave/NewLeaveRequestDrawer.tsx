import { useEffect, useState } from 'react';
import { Alert, Button, Col, DatePicker, Descriptions, Form, Input, InputNumber, Row, Select, Switch, TimePicker, Typography } from 'antd';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { DataTable, FormDrawer, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { EmployeeSelect } from '../../common';
import { DAY_PARTS, TREATMENT_TYPES, type DayPart, type LeavePreview, type LeaveRequestBody, type LeaveRequestRow, type SiClaimInput } from './api';
import { useLeaveTypes, usePreviewLeave, useSubmitLeave } from './queries';
import { applyFieldErrors, enumOptions, formatQty, LeaveTypeSelect, NS, toIso, useEnumLabel, useWarningText } from './components';

interface SiForm {
  certificateType?: string;
  certificateNo?: string;
  certificateDate?: Dayjs | null;
  issuingFacility?: string;
  diagnosisCategory?: string;
  longTermIllness?: boolean;
  treatmentType?: string;
  childBirthDate?: Dayjs | null;
  childId?: string;
  confinementDate?: Dayjs | null;
  childrenBornCount?: number | null;
  caesarean?: boolean;
}

interface RequestForm {
  employmentId: UUID;
  leaveTypeId: UUID;
  startDate: Dayjs;
  endDate?: Dayjs | null;
  startPart?: DayPart;
  endPart?: DayPart;
  startTime?: Dayjs | null;
  endTime?: Dayjs | null;
  reason?: string;
  attachmentDocumentIds?: string[];
  si?: SiForm;
}

export function siFormToInput(si: SiForm | undefined): SiClaimInput | null {
  if (!si || Object.values(si).every((v) => v === undefined || v === null || v === '')) return null;
  return {
    certificateType: si.certificateType || null,
    certificateNo: si.certificateNo || null,
    certificateDate: toIso(si.certificateDate),
    issuingFacility: si.issuingFacility || null,
    diagnosisCategory: si.diagnosisCategory || null,
    longTermIllness: si.longTermIllness ?? null,
    treatmentType: si.treatmentType || null,
    childBirthDate: toIso(si.childBirthDate),
    childId: si.childId || null,
    confinementDate: toIso(si.confinementDate),
    childrenBornCount: si.childrenBornCount ?? null,
    caesarean: si.caesarean ?? null,
  };
}

/** SI claim fields (BR-ATT-063); `category` decides the child / maternity fields. Used in a `Form` under `prefix`. */
export function SiClaimFields({ prefix, category }: { prefix: string[]; category?: string }) {
  const { t } = useTranslation(NS);
  const label = useEnumLabel();
  const n = (k: string) => [...prefix, k];
  const childish = category === 'CHILD_SICK' || category === 'MATERNITY' || category === 'PATERNITY' || category === 'MISCARRIAGE';
  const birth = category === 'MATERNITY' || category === 'PATERNITY' || category === 'MISCARRIAGE';
  return (
    <Row gutter={12}>
      <Col span={12}>
        <Form.Item name={n('certificateType')} label={t('si.certificateType')} rules={[{ required: true, message: t('si.certificateTypeRequired') }]}>
          <Input placeholder={t('si.certificateTypeHint')} />
        </Form.Item>
      </Col>
      <Col span={12}>
        <Form.Item name={n('certificateNo')} label={t('si.certificateNo')}>
          <Input autoComplete="off" />
        </Form.Item>
      </Col>
      <Col span={12}>
        <Form.Item name={n('certificateDate')} label={t('si.certificateDate')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Col>
      <Col span={12}>
        <Form.Item name={n('issuingFacility')} label={t('si.issuingFacility')}>
          <Input autoComplete="off" />
        </Form.Item>
      </Col>
      <Col span={12}>
        <Form.Item name={n('diagnosisCategory')} label={t('si.diagnosisCategory')}>
          <Input autoComplete="off" />
        </Form.Item>
      </Col>
      <Col span={8}>
        <Form.Item name={n('treatmentType')} label={t('si.treatmentType')}>
          <Select allowClear options={enumOptions(label, 'treatment', TREATMENT_TYPES)} />
        </Form.Item>
      </Col>
      <Col span={4}>
        <Form.Item name={n('longTermIllness')} label={t('si.longTermIllness')} valuePropName="checked">
          <Switch />
        </Form.Item>
      </Col>
      {childish ? (
        <>
          <Col span={12}>
            <Form.Item
              name={n('childBirthDate')}
              label={t('si.childBirthDate')}
              rules={category === 'CHILD_SICK' ? [{ required: true, message: t('si.childBirthDateRequired') }] : undefined}
            >
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name={n('childId')} label={t('si.childId')}>
              <Input autoComplete="off" />
            </Form.Item>
          </Col>
        </>
      ) : null}
      {birth ? (
        <>
          <Col span={8}>
            <Form.Item name={n('confinementDate')} label={t('si.confinementDate')}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name={n('childrenBornCount')} label={t('si.childrenBornCount')}>
              <InputNumber min={1} max={10} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name={n('caesarean')} label={t('si.caesarean')} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Col>
        </>
      ) : null}
    </Row>
  );
}

/**
 * "New leave request" on behalf of an employee (BR-ATT-061/062): the employee, type and dates, then a preview of the
 * counted days and the balance left, then the submit to Core's workflow. Any change to the form drops the preview.
 */
export function NewLeaveRequestDrawer({
  open,
  onClose,
  employmentId,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  employmentId?: UUID;
  onCreated?: (row: LeaveRequestRow) => void;
}) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const label = useEnumLabel();
  const fb = useFeedback();
  const [form] = Form.useForm<RequestForm>();
  const types = useLeaveTypes();
  const preview = usePreviewLeave();
  const submit = useSubmitLeave();
  const [result, setResult] = useState<LeavePreview | null>(null);
  const typeId = Form.useWatch('leaveTypeId', form);
  const type = types.data?.find((x) => x.id === typeId);
  const unit = type?.data.minUnit ?? 'DAY';

  useEffect(() => {
    if (open) {
      form.resetFields();
      form.setFieldsValue({ employmentId, startPart: 'FULL', endPart: 'FULL' });
    }
  }, [open, employmentId, form]);

  const close = () => {
    setResult(null);
    preview.reset();
    submit.reset();
    onClose();
  };

  const body = (v: RequestForm): LeaveRequestBody => {
    const hourly = unit === 'HOUR';
    return {
      leaveTypeId: v.leaveTypeId,
      startDate: toIso(v.startDate) as string,
      endDate: hourly ? toIso(v.startDate) : (toIso(v.endDate) ?? toIso(v.startDate)),
      startPart: hourly ? null : (v.startPart ?? 'FULL'),
      endPart: hourly ? null : (v.endPart ?? 'FULL'),
      startTime: hourly && v.startTime ? v.startTime.format('HH:mm') : null,
      endTime: hourly && v.endTime ? v.endTime.format('HH:mm') : null,
      reason: v.reason || null,
      attachmentDocumentIds: v.attachmentDocumentIds?.length ? v.attachmentDocumentIds : null,
      siClaim: type?.data.requiresSiClaimData ? siFormToInput(v.si) : null,
    };
  };

  const run = async () => {
    let v: RequestForm;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      if (!result) {
        setResult(await preview.mutateAsync({ employmentId: v.employmentId, body: body(v) }));
        return;
      }
      const row = await submit.mutateAsync({ employmentId: v.employmentId, body: body(v) });
      fb.done(t('requests.submitted'));
      close();
      onCreated?.(row);
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const warn = useWarningText();
  const required = { required: true, message: tc('common.required') };
  const years = result ? Object.keys(result.daysPerLeaveYear) : [];

  return (
    <FormDrawer
      open={open}
      onClose={close}
      title={t('requests.new')}
      onSubmit={() => void run()}
      submitting={preview.isPending || submit.isPending}
      error={submit.error ?? preview.error}
      submitLabel={result ? t('requests.submit') : t('requests.preview')}
      footerExtra={
        result ? (
          <Button onClick={() => setResult(null)} disabled={submit.isPending}>
            {t('requests.editAgain')}
          </Button>
        ) : null
      }
      width={720}
    >
      <Form<RequestForm> form={form} layout="vertical" disabled={!!result} onValuesChange={() => setResult(null)}>
        <Form.Item name="employmentId" label={t('field.employee')} rules={[required]}>
          <EmployeeSelect />
        </Form.Item>
        <Form.Item name="leaveTypeId" label={t('field.leaveType')} rules={[required]}>
          <LeaveTypeSelect activeOnly />
        </Form.Item>
        <Row gutter={12}>
          <Col span={unit === 'HOUR' ? 8 : 12}>
            <Form.Item name="startDate" label={unit === 'HOUR' ? t('field.date') : t('field.startDate')} rules={[required]}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          {unit === 'HOUR' ? (
            <>
              <Col span={8}>
                <Form.Item name="startTime" label={t('field.startTime')} rules={[required]}>
                  <TimePicker format="HH:mm" minuteStep={15} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="endTime" label={t('field.endTime')} rules={[required]}>
                  <TimePicker format="HH:mm" minuteStep={15} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </>
          ) : (
            <Col span={12}>
              <Form.Item name="endDate" label={t('field.endDate')} extra={t('requests.endDateHint')}>
                <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          )}
          {unit === 'HALF_DAY' ? (
            <>
              <Col span={12}>
                <Form.Item name="startPart" label={t('field.startPart')}>
                  <Select options={enumOptions(label, 'part', DAY_PARTS)} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="endPart" label={t('field.endPart')}>
                  <Select options={enumOptions(label, 'part', DAY_PARTS)} />
                </Form.Item>
              </Col>
            </>
          ) : null}
        </Row>
        <Form.Item name="reason" label={tc('field.reason')}>
          <Input.TextArea rows={2} maxLength={1000} />
        </Form.Item>
        <Form.Item
          name="attachmentDocumentIds"
          label={t('field.attachments')}
          extra={t('requests.attachmentsHint')}
          rules={type?.data.requiresAttachment ? [{ required: true, type: 'array', min: 1, message: t('requests.attachmentRequired') }] : undefined}
        >
          <Select mode="tags" tokenSeparators={[',', ' ']} open={false} />
        </Form.Item>
        {type?.data.requiresSiClaimData ? (
          <>
            <Typography.Title level={5}>{t('si.title')}</Typography.Title>
            <SiClaimFields prefix={['si']} category={type.data.category} />
          </>
        ) : null}
      </Form>
      {result ? (
        <div data-testid="leave-preview">
          <Typography.Title level={5}>{t('requests.previewTitle')}</Typography.Title>
          <Descriptions size="small" bordered column={2} style={{ marginBottom: 12 }}>
            <Descriptions.Item label={t('field.duration')}>
              {result.requestUnit === 'HOUR'
                ? t('requests.hoursValue', { value: formatQty(result.durationHours) })
                : t('requests.daysValue', { value: formatQty(result.durationDays) })}
            </Descriptions.Item>
            <Descriptions.Item label={t('field.unit')}>{label('unit', result.requestUnit)}</Descriptions.Item>
          </Descriptions>
          {years.length ? (
            <DataTable<{ year: string }>
              rowKey="year"
              data={years.map((year) => ({ year }))}
              columns={[
                { title: t('field.leaveYear'), dataIndex: 'year' },
                { title: t('requests.daysRequested'), key: 'd', align: 'right', render: (_, r) => formatQty(result.daysPerLeaveYear[r.year]) },
                {
                  title: t('requests.availableAfter'),
                  key: 'a',
                  align: 'right',
                  render: (_, r) => (r.year in result.available ? formatQty(result.available[r.year]) : t('requests.notTracked')),
                },
              ]}
            />
          ) : null}
          {result.days.length ? (
            <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
              {t('requests.countedDays')}:{' '}
              {result.days
                .filter((d) => d.quantity > 0)
                .map((d) => `${formatDate(d.date)}${d.part && d.part !== 'FULL' ? ` (${label('part', d.part)})` : ''}`)
                .join(', ')}
            </Typography.Paragraph>
          ) : null}
          {result.warnings.map((w) => (
            <Alert key={w} type="warning" showIcon message={warn(w)} style={{ marginTop: 8 }} />
          ))}
        </div>
      ) : null}
    </FormDrawer>
  );
}
