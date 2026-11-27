import { useState } from 'react';
import { Checkbox, Col, DatePicker, Form, Input, InputNumber, Modal, Radio, Row, Select } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { FormDrawer, ProblemAlert, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { LegalEntitySelect, LocationSelect } from '../../common';
import {
  CALENDAR_CODE_PATTERN,
  DAY_TYPES,
  ELIGIBILITIES,
  HOLIDAY_CODE_PATTERN,
  type CalendarAssignment,
  type CalendarStatus,
  type DayType,
  type Eligibility,
  type HolidayCalendar,
  type HolidayDate,
} from './api';
import { useAssignCalendar, useCreateCalendar, useEndCalendarAssignment, useHolidayTemplates, useSaveDate, useUpdateCalendar } from './queries';
import { clean, fromExclusiveEnd, iso, toExclusiveEnd, useName } from './helpers';
import { CALENDARS_NS } from './i18n';

// ------------------------------------------------------------------------------------------------ create

interface CreateValues {
  kind: 'CUSTOM' | 'TEMPLATE';
  code: string;
  nameVi?: string;
  nameEn?: string;
  templateId?: UUID;
  year?: number;
}

/** A CUSTOM calendar or a TENANT_COPY of a platform template filled for one year (BR-ATT-001/002, US-ATT-001). */
export function CreateCalendarDrawer({ onClose, onSaved }: { onClose: () => void; onSaved: (c: HolidayCalendar) => void }) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const fb = useFeedback();
  const name = useName();
  const [form] = Form.useForm<CreateValues>();
  const create = useCreateCalendar();
  const templates = useHolidayTemplates();
  const kind = Form.useWatch('kind', form);
  const templateId = Form.useWatch('templateId', form);
  const template = templates.data?.find((x) => x.id === templateId);

  const submit = async () => {
    let v: CreateValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const fromTemplate = v.kind === 'TEMPLATE';
    try {
      const saved = await create.mutateAsync({
        code: v.code.trim().toUpperCase(),
        nameVi: clean(v.nameVi),
        nameEn: clean(v.nameEn),
        templateId: fromTemplate ? v.templateId : null,
        year: fromTemplate ? (v.year ?? null) : null,
      });
      fb.created();
      onSaved(saved);
      onClose();
    } catch {
      // shown by the drawer
    }
  };

  return (
    <FormDrawer open onClose={onClose} title={tc('calendar.create')} onSubmit={() => void submit()} submitting={create.isPending} error={create.error}>
      <Form<CreateValues> form={form} layout="vertical" initialValues={{ kind: 'TEMPLATE', year: dayjs().year() }}>
        <Form.Item name="kind" label={tc('calendar.field.kind')}>
          <Radio.Group
            options={[
              { value: 'TEMPLATE', label: tc('calendar.kind.TEMPLATE') },
              { value: 'CUSTOM', label: tc('calendar.kind.CUSTOM') },
            ]}
          />
        </Form.Item>
        {kind === 'TEMPLATE' ? (
          <Row gutter={16}>
            <Col xs={24} md={16}>
              <Form.Item name="templateId" label={tc('calendar.field.template')} rules={[{ required: true, message: tc('calendar.templateRequired') }]}>
                <Select
                  loading={templates.isLoading}
                  options={(templates.data ?? []).map((x) => ({ value: x.id, label: `${x.code} – ${name(x)}` }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="year" label={tc('calendar.field.year')} extra={tc('calendar.yearHint')}>
                {template && template.years.length > 0 ? (
                  <Select allowClear options={template.years.map((y) => ({ value: y, label: String(y) }))} />
                ) : (
                  <InputNumber min={2000} max={2100} precision={0} style={{ width: '100%' }} />
                )}
              </Form.Item>
            </Col>
          </Row>
        ) : null}
        <Form.Item
          name="code"
          label={t('field.code')}
          extra={tc('calendar.codeHint')}
          rules={[
            { required: true, whitespace: true, message: tc('common.codeRequired') },
            { pattern: CALENDAR_CODE_PATTERN, message: tc('calendar.codeInvalid') },
          ]}
        >
          <Input autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item
          name="nameVi"
          label={t('field.nameVi')}
          extra={kind === 'TEMPLATE' ? tc('calendar.nameFromTemplate') : undefined}
          rules={[{ required: kind === 'CUSTOM', whitespace: true, message: tc('common.nameRequired') }, { max: 200 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item name="nameEn" label={t('field.nameEn')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ edit

interface EditValues {
  nameVi: string;
  nameEn?: string;
  status: CalendarStatus;
}

export function EditCalendarDrawer({ calendar, onClose }: { calendar: HolidayCalendar; onClose: () => void }) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<EditValues>();
  const update = useUpdateCalendar(calendar.id);

  const submit = async () => {
    let v: EditValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await update.mutateAsync({ nameVi: v.nameVi.trim(), nameEn: clean(v.nameEn) ?? '', status: v.status, version: calendar.version });
      fb.saved();
      onClose();
    } catch {
      // shown by the drawer
    }
  };

  return (
    <FormDrawer open onClose={onClose} title={tc('calendar.editTitle', { code: calendar.code })} onSubmit={() => void submit()} submitting={update.isPending} error={update.error}>
      <Form<EditValues>
        form={form}
        layout="vertical"
        initialValues={{ nameVi: calendar.nameVi, nameEn: calendar.nameEn ?? undefined, status: calendar.status }}
      >
        <Form.Item name="nameVi" label={t('field.nameVi')} rules={[{ required: true, whitespace: true, message: tc('common.nameRequired') }, { max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="nameEn" label={t('field.nameEn')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="status" label={t('field.status')} extra={tc('calendar.statusHint')}>
          <Select
            options={(['ACTIVE', 'INACTIVE'] as const).map((s) => ({ value: s, label: t(`status.${s}`) }))}
          />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ dates

interface DateValues {
  holidayDate: Dayjs;
  dayType: DayType;
  holidayCode?: string;
  nameVi: string;
  nameEn?: string;
  lunarMonth?: number | null;
  lunarDay?: number | null;
  lunarLeapMonth?: boolean;
  substituteForDate?: Dayjs | null;
  paid: boolean;
  eligibility: Eligibility;
  nationalityCode?: string;
  legalRef?: string;
  reason?: string;
}

/** Add a date, or replace one (template override: `version` and `reason` required). */
export function DateDrawer({
  calendarId,
  date,
  defaultYear,
  onClose,
}: {
  calendarId: UUID;
  date?: HolidayDate;
  defaultYear: number;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<DateValues>();
  const save = useSaveDate(calendarId);
  const dayType = Form.useWatch('dayType', form);
  const eligibility = Form.useWatch('eligibility', form);
  const editing = !!date;

  const initialValues: Partial<DateValues> = date
    ? {
        holidayDate: dayjs(date.holidayDate),
        dayType: date.dayType,
        holidayCode: date.holidayCode ?? undefined,
        nameVi: date.nameVi,
        nameEn: date.nameEn ?? undefined,
        lunarMonth: date.lunarMonth ?? null,
        lunarDay: date.lunarDay ?? null,
        lunarLeapMonth: date.lunarLeapMonth,
        substituteForDate: date.substituteForDate ? dayjs(date.substituteForDate) : null,
        paid: date.paid,
        eligibility: date.eligibility,
        nationalityCode: date.nationalityCode ?? undefined,
        legalRef: date.legalRef ?? undefined,
      }
    : { dayType: 'COMPANY', paid: true, eligibility: 'ALL', lunarLeapMonth: false, holidayDate: dayjs(`${defaultYear}-01-01`) };

  const submit = async () => {
    let v: DateValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const foreigners = v.eligibility === 'FOREIGNERS';
    try {
      await save.mutateAsync({
        dateId: date?.id,
        body: {
          holidayDate: iso(v.holidayDate) as string,
          dayType: v.dayType,
          holidayCode: clean(v.holidayCode)?.toUpperCase() ?? null,
          nameVi: v.nameVi.trim(),
          nameEn: clean(v.nameEn),
          lunarMonth: v.lunarMonth ?? null,
          lunarDay: v.lunarDay ?? null,
          lunarLeapMonth: !!v.lunarLeapMonth,
          substituteForDate: v.dayType === 'SUBSTITUTE' ? iso(v.substituteForDate) : null,
          paid: v.paid,
          eligibility: v.eligibility,
          nationalityCode: foreigners ? (clean(v.nationalityCode)?.toUpperCase() ?? null) : null,
          legalRef: clean(v.legalRef),
          version: date?.version ?? null,
          reason: editing ? clean(v.reason) : null,
        },
      });
      if (editing) fb.saved();
      else fb.created();
      onClose();
    } catch {
      // shown by the drawer
    }
  };

  return (
    <FormDrawer
      open
      onClose={onClose}
      width={640}
      title={editing ? tc('date.editTitle', { date: formatDate(date.holidayDate) }) : tc('date.create')}
      onSubmit={() => void submit()}
      submitting={save.isPending}
      error={save.error}
    >
      <Form<DateValues> form={form} layout="vertical" initialValues={initialValues}>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item name="holidayDate" label={tc('date.field.holidayDate')} rules={[{ required: true, message: tc('date.dateRequired') }]}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="dayType" label={tc('date.field.dayType')} extra={tc('date.swapHint')} rules={[{ required: true }]}>
              <Select options={DAY_TYPES.map((d) => ({ value: d, label: tc(`dayType.${d}`) }))} />
            </Form.Item>
          </Col>
          {dayType === 'SUBSTITUTE' ? (
            <Col xs={24} md={12}>
              <Form.Item
                name="substituteForDate"
                label={tc('date.field.substituteForDate')}
                extra={tc('date.substituteHint')}
                rules={[{ required: true, message: tc('date.substituteRequired') }]}
              >
                <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          ) : null}
          <Col xs={24} md={12}>
            <Form.Item
              name="holidayCode"
              label={tc('date.field.holidayCode')}
              rules={[{ pattern: HOLIDAY_CODE_PATTERN, message: tc('date.codeInvalid') }]}
            >
              <Input autoComplete="off" style={{ fontFamily: 'monospace' }} placeholder="TET_DL" />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="nameVi" label={t('field.nameVi')} rules={[{ required: true, whitespace: true, message: tc('common.nameRequired') }, { max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="nameEn" label={t('field.nameEn')} rules={[{ max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="lunarMonth" label={tc('date.field.lunarMonth')}>
              <InputNumber min={1} max={12} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="lunarDay" label={tc('date.field.lunarDay')}>
              <InputNumber min={1} max={30} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="lunarLeapMonth" valuePropName="checked" label=" ">
              <Checkbox>{tc('date.field.lunarLeapMonth')}</Checkbox>
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="eligibility" label={tc('date.field.eligibility')}>
              <Select options={ELIGIBILITIES.map((e) => ({ value: e, label: tc(`eligibility.${e}`) }))} />
            </Form.Item>
          </Col>
          {eligibility === 'FOREIGNERS' ? (
            <Col xs={24} md={12}>
              <Form.Item
                name="nationalityCode"
                label={tc('date.field.nationalityCode')}
                extra={tc('date.nationalityHint')}
                rules={[
                  { required: true, message: tc('date.nationalityRequired') },
                  { pattern: /^[A-Za-z]{2}$/, message: tc('date.nationalityInvalid') },
                ]}
              >
                <Input maxLength={2} style={{ width: 80, textTransform: 'uppercase' }} />
              </Form.Item>
            </Col>
          ) : null}
          <Col xs={24}>
            <Form.Item name="paid" valuePropName="checked">
              <Checkbox>{tc('date.field.paid')}</Checkbox>
            </Form.Item>
          </Col>
          <Col xs={24}>
            <Form.Item name="legalRef" label={tc('date.field.legalRef')} rules={[{ max: 500 }]}>
              <Input />
            </Form.Item>
          </Col>
          {editing ? (
            <Col xs={24}>
              <Form.Item
                name="reason"
                label={t('field.reason')}
                extra={tc('date.reasonHint')}
                rules={[{ required: true, whitespace: true, message: tc('common.reasonRequired') }, { max: 500 }]}
              >
                <Input.TextArea rows={2} />
              </Form.Item>
            </Col>
          ) : null}
        </Row>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ assignments

interface AssignValues {
  legalEntityId: UUID;
  locationId?: UUID;
  validFrom: Dayjs;
  lastDay?: Dayjs | null;
}

/** One calendar per legal entity (+ optional location) at any date (Pattern A). */
export function AssignDrawer({ calendarId, onClose }: { calendarId: UUID; onClose: () => void }) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<AssignValues>();
  const assign = useAssignCalendar(calendarId);
  const legalEntityId = Form.useWatch('legalEntityId', form);

  const submit = async () => {
    let v: AssignValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await assign.mutateAsync({
        legalEntityId: v.legalEntityId,
        locationId: v.locationId ?? null,
        validFrom: iso(v.validFrom) as string,
        validTo: toExclusiveEnd(v.lastDay),
      });
      fb.created();
      onClose();
    } catch {
      // shown by the drawer
    }
  };

  return (
    <FormDrawer open onClose={onClose} title={tc('assignment.create')} onSubmit={() => void submit()} submitting={assign.isPending} error={assign.error}>
      <Form<AssignValues> form={form} layout="vertical" initialValues={{ validFrom: dayjs().startOf('year') }}>
        <Form.Item name="legalEntityId" label={t('field.legalEntity')} rules={[{ required: true, message: tc('common.legalEntityRequired') }]}>
          <LegalEntitySelect onChange={() => form.setFieldValue('locationId', undefined)} />
        </Form.Item>
        <Form.Item name="locationId" label={t('field.location')} extra={tc('assignment.locationHint')}>
          <LocationSelect legalEntityId={legalEntityId} />
        </Form.Item>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item name="validFrom" label={t('field.validFrom')} rules={[{ required: true, message: tc('common.validFromRequired') }]}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="lastDay" label={tc('common.lastDay')} extra={tc('common.lastDayHint')}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </FormDrawer>
  );
}

/** Sets (or clears) the end of an assignment; it cannot be removed. */
export function EndAssignmentModal({
  calendarId,
  assignment,
  onClose,
}: {
  calendarId: UUID;
  assignment: CalendarAssignment;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(CALENDARS_NS);
  const fb = useFeedback();
  const end = useEndCalendarAssignment(calendarId);
  const [lastDay, setLastDay] = useState<Dayjs | null>(fromExclusiveEnd(assignment.validTo));
  const submit = async () => {
    try {
      await end.mutateAsync({ assignmentId: assignment.id, body: { validTo: toExclusiveEnd(lastDay), version: assignment.version } });
      fb.saved();
      onClose();
    } catch {
      // shown in the modal
    }
  };
  return (
    <Modal
      open
      title={tc('assignment.endTitle')}
      onCancel={onClose}
      onOk={() => void submit()}
      okText={t('action.save')}
      cancelText={t('action.cancel')}
      confirmLoading={end.isPending}
    >
      {end.error ? <ProblemAlert error={end.error} style={{ marginBottom: 12 }} /> : null}
      <p>{tc('assignment.endHint', { from: formatDate(assignment.validFrom) })}</p>
      <DatePicker
        aria-label={tc('common.lastDay')}
        format={DATE_FORMAT}
        value={lastDay}
        onChange={setLastDay}
        style={{ width: '100%' }}
        placeholder={tc('common.openEnded')}
      />
    </Modal>
  );
}
