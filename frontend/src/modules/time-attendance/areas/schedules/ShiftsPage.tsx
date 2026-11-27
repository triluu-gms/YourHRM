import { useState } from 'react';
import { Button, Checkbox, Col, Divider, Form, Input, InputNumber, Row, Select, Space, Tag, TimePicker, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { AttendancePermissions as P } from '../../permissions';
import { CONFIG_CODE_PATTERN, type ConfigStatus, type Shift, type ShiftResult } from './api';
import { useSaveShift, useShifts } from './queries';
import { clean, fromTime, hhmm, minutesLabel, TIME_FORMAT, toTime, WarningsAlert } from './helpers';
import { SCHEDULES_NS } from './i18n';

/** Shift definitions (BR-ATT-010, US-ATT-008): times, breaks, grace, punch windows, standard hours and day value. */
export function ShiftsPage() {
  const { t, i18n } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const canUpdate = usePermission(P.SCHEDULE_CONFIG_UPDATE);
  const query = useShifts();
  const [editing, setEditing] = useState<Shift | 'new' | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const name = (s: Shift) => (i18n.language.startsWith('en') ? s.nameEn || s.nameVi : s.nameVi);

  return (
    <div>
      <PageHeader
        title={ts('shift.title')}
        subTitle={ts('shift.subtitle')}
        extra={
          <PermissionGate anyOf={P.SCHEDULE_CONFIG_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>
              {ts('shift.create')}
            </Button>
          </PermissionGate>
        }
      />
      <WarningsAlert warnings={warnings} onClose={() => setWarnings([])} />
      <DataTable<Shift>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: ts('shift.empty') }}
        columns={[
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: t('field.name'), key: 'name', render: (_, s) => name(s) },
          {
            title: ts('shift.field.time'),
            key: 'time',
            render: (_, s) => (
              <Space size={4}>
                {`${hhmm(s.startTime)} – ${hhmm(s.endTime)}`}
                {s.crossesMidnight ? <Tag color="purple">{ts('shift.crossesMidnight')}</Tag> : null}
              </Space>
            ),
          },
          { title: ts('shift.field.workingMinutes'), dataIndex: 'workingMinutes', render: (m: number) => minutesLabel(m) },
          { title: ts('shift.field.stdHours'), dataIndex: 'stdHours' },
          { title: ts('shift.field.dayValue'), dataIndex: 'dayValue' },
          { title: ts('shift.field.breaks'), key: 'breaks', render: (_, s) => s.breaks.length },
          { title: ts('shift.field.shiftGroupCode'), dataIndex: 'shiftGroupCode' },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, s: Shift) => (
                    <Button size="small" onClick={() => setEditing(s)}>
                      {t('action.edit')}
                    </Button>
                  ),
                },
              ]
            : []),
        ]}
      />
      {editing ? (
        <ShiftDrawer
          shift={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={(r) => setWarnings(r.warnings)}
        />
      ) : null}
    </div>
  );
}

interface BreakValues {
  start?: Dayjs | null;
  end?: Dayjs | null;
  durationMin?: number | null;
  paid?: boolean;
  flexible?: boolean;
}

interface ShiftValues {
  code?: string;
  nameVi: string;
  nameEn?: string;
  startTime: Dayjs;
  endTime: Dayjs;
  graceInMin?: number | null;
  graceOutMin?: number | null;
  roundingRuleCode?: string;
  punchWindowBeforeMin?: number | null;
  punchWindowAfterMin?: number | null;
  minMinutesHalfDay?: number | null;
  minMinutesFullDay?: number | null;
  stdHours?: number | null;
  dayValue?: number | null;
  nightWindowStart?: Dayjs | null;
  nightWindowEnd?: Dayjs | null;
  shiftGroupCode?: string;
  breaks: BreakValues[];
  status?: ConfigStatus;
}

export function ShiftDrawer({ shift, onClose, onSaved }: { shift?: Shift; onClose: () => void; onSaved: (r: ShiftResult) => void }) {
  const { t } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<ShiftValues>();
  const save = useSaveShift();
  const editing = !!shift;

  const initialValues: Partial<ShiftValues> = shift
    ? {
        nameVi: shift.nameVi,
        nameEn: shift.nameEn ?? undefined,
        startTime: toTime(shift.startTime) as Dayjs,
        endTime: toTime(shift.endTime) as Dayjs,
        graceInMin: shift.graceInMin,
        graceOutMin: shift.graceOutMin,
        roundingRuleCode: shift.roundingRuleCode ?? undefined,
        punchWindowBeforeMin: shift.punchWindowBeforeMin,
        punchWindowAfterMin: shift.punchWindowAfterMin,
        minMinutesHalfDay: shift.minMinutesHalfDay ?? null,
        minMinutesFullDay: shift.minMinutesFullDay ?? null,
        stdHours: shift.stdHours,
        dayValue: shift.dayValue,
        nightWindowStart: toTime(shift.nightWindowStart),
        nightWindowEnd: toTime(shift.nightWindowEnd),
        shiftGroupCode: shift.shiftGroupCode ?? undefined,
        breaks: shift.breaks.map((b) => ({ start: toTime(b.start), end: toTime(b.end), durationMin: b.durationMin, paid: b.paid, flexible: b.flexible })),
        status: shift.status,
      }
    : { graceInMin: 0, graceOutMin: 0, punchWindowBeforeMin: 120, punchWindowAfterMin: 240, dayValue: 1, breaks: [] };

  const submit = async () => {
    let v: ShiftValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const result = await save.mutateAsync({
        id: shift?.id,
        body: {
          code: editing ? shift.code : (clean(v.code)?.toUpperCase() ?? null),
          nameVi: v.nameVi.trim(),
          nameEn: clean(v.nameEn),
          startTime: fromTime(v.startTime) as string,
          endTime: fromTime(v.endTime) as string,
          graceInMin: v.graceInMin ?? null,
          graceOutMin: v.graceOutMin ?? null,
          roundingRuleCode: clean(v.roundingRuleCode),
          punchWindowBeforeMin: v.punchWindowBeforeMin ?? null,
          punchWindowAfterMin: v.punchWindowAfterMin ?? null,
          minMinutesHalfDay: v.minMinutesHalfDay ?? null,
          minMinutesFullDay: v.minMinutesFullDay ?? null,
          stdHours: v.stdHours ?? null,
          dayValue: v.dayValue ?? null,
          nightWindowStart: fromTime(v.nightWindowStart),
          nightWindowEnd: fromTime(v.nightWindowEnd),
          shiftGroupCode: clean(v.shiftGroupCode),
          breaks: (v.breaks ?? []).map((b) => ({
            start: b.flexible ? null : fromTime(b.start),
            end: b.flexible ? null : fromTime(b.end),
            durationMin: b.durationMin ?? null,
            paid: !!b.paid,
            flexible: !!b.flexible,
          })),
          status: editing ? (v.status ?? null) : null,
          version: shift?.version ?? null,
        },
      });
      if (editing) fb.saved();
      else fb.created();
      onSaved(result);
      onClose();
    } catch {
      // shown by the drawer
    }
  };

  const minutes = (max: number) => <InputNumber min={0} max={max} precision={0} style={{ width: '100%' }} addonAfter={ts('common.min')} />;

  return (
    <FormDrawer
      open
      onClose={onClose}
      width={760}
      title={editing ? ts('shift.editTitle', { code: shift.code }) : ts('shift.create')}
      onSubmit={() => void submit()}
      submitting={save.isPending}
      error={save.error}
    >
      <Form<ShiftValues> form={form} layout="vertical" initialValues={initialValues}>
        <Row gutter={16}>
          <Col xs={24} md={8}>
            <CodeItem code={editing ? shift.code : undefined} />
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="nameVi" label={t('field.nameVi')} rules={[{ required: true, whitespace: true, message: ts('common.nameRequired') }, { max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="nameEn" label={t('field.nameEn')} rules={[{ max: 200 }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="startTime" label={ts('shift.field.startTime')} rules={[{ required: true, message: ts('shift.timeRequired') }]}>
              <TimePicker format={TIME_FORMAT} minuteStep={5} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="endTime" label={ts('shift.field.endTime')} extra={ts('shift.endTimeHint')} rules={[{ required: true, message: ts('shift.timeRequired') }]}>
              <TimePicker format={TIME_FORMAT} minuteStep={5} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="stdHours" label={ts('shift.field.stdHours')} extra={ts('shift.stdHoursHint')}>
              <InputNumber min={0} max={24} step={0.5} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="dayValue" label={ts('shift.field.dayValue')} extra={ts('shift.dayValueHint')}>
              <InputNumber min={0} max={1} step={0.5} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left">{ts('shift.section.breaks')}</Divider>
        <Form.List name="breaks">
          {(fields, { add, remove }) => (
            <>
              {fields.map((f) => (
                <Row gutter={8} key={f.key} align="middle">
                  <Col xs={12} md={5}>
                    <Form.Item name={[f.name, 'start']} label={ts('shift.field.breakStart')}>
                      <TimePicker format={TIME_FORMAT} minuteStep={5} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col xs={12} md={5}>
                    <Form.Item name={[f.name, 'end']} label={ts('shift.field.breakEnd')}>
                      <TimePicker format={TIME_FORMAT} minuteStep={5} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col xs={12} md={5}>
                    <Form.Item name={[f.name, 'durationMin']} label={ts('shift.field.breakDuration')}>
                      {minutes(720)}
                    </Form.Item>
                  </Col>
                  <Col xs={12} md={7}>
                    <Space>
                      <Form.Item name={[f.name, 'paid']} valuePropName="checked" style={{ marginBottom: 0 }}>
                        <Checkbox>{ts('shift.field.breakPaid')}</Checkbox>
                      </Form.Item>
                      <Form.Item name={[f.name, 'flexible']} valuePropName="checked" style={{ marginBottom: 0 }}>
                        <Checkbox>{ts('shift.field.breakFlexible')}</Checkbox>
                      </Form.Item>
                    </Space>
                  </Col>
                  <Col xs={24} md={2}>
                    <Button type="text" danger aria-label={t('action.delete')} icon={<DeleteOutlined />} onClick={() => remove(f.name)} />
                  </Col>
                </Row>
              ))}
              <Typography.Paragraph type="secondary">{ts('shift.breaksHint')}</Typography.Paragraph>
              <Button icon={<PlusOutlined />} onClick={() => add({ paid: false, flexible: false })}>
                {ts('shift.addBreak')}
              </Button>
            </>
          )}
        </Form.List>

        <Divider orientation="left">{ts('shift.section.punch')}</Divider>
        <Row gutter={16}>
          <Col xs={12} md={6}>
            <Form.Item name="graceInMin" label={ts('shift.field.graceInMin')}>
              {minutes(240)}
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="graceOutMin" label={ts('shift.field.graceOutMin')}>
              {minutes(240)}
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="punchWindowBeforeMin" label={ts('shift.field.punchWindowBeforeMin')}>
              {minutes(720)}
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="punchWindowAfterMin" label={ts('shift.field.punchWindowAfterMin')}>
              {minutes(720)}
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="minMinutesHalfDay" label={ts('shift.field.minMinutesHalfDay')}>
              {minutes(1440)}
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="minMinutesFullDay" label={ts('shift.field.minMinutesFullDay')}>
              {minutes(1440)}
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="roundingRuleCode" label={ts('shift.field.roundingRuleCode')} rules={[{ max: 40 }]}>
              <Input style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="shiftGroupCode" label={ts('shift.field.shiftGroupCode')} rules={[{ max: 40 }]}>
              <Input style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left">{ts('shift.section.night')}</Divider>
        <Typography.Paragraph type="secondary">{ts('shift.nightHint')}</Typography.Paragraph>
        <Row gutter={16}>
          <Col xs={12} md={6}>
            <Form.Item name="nightWindowStart" label={ts('shift.field.nightWindowStart')}>
              <TimePicker format={TIME_FORMAT} minuteStep={5} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="nightWindowEnd" label={ts('shift.field.nightWindowEnd')}>
              <TimePicker format={TIME_FORMAT} minuteStep={5} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          {editing ? (
            <Col xs={24} md={12}>
              <Form.Item name="status" label={t('field.status')} extra={ts('common.statusHint')}>
                <Select options={(['ACTIVE', 'INACTIVE'] as const).map((s) => ({ value: s, label: t(`status.${s}`) }))} />
              </Form.Item>
            </Col>
          ) : null}
        </Row>
      </Form>
    </FormDrawer>
  );
}

/** The code field of a create form, or the read-only code of an edited record (codes never change). */
export function CodeItem({ code }: { code?: string }) {
  const { t } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  if (code) {
    return (
      <Form.Item label={t('field.code')}>
        <Typography.Text code>{code}</Typography.Text>
      </Form.Item>
    );
  }
  return (
    <Form.Item
      name="code"
      label={t('field.code')}
      extra={ts('common.codeHint')}
      rules={[{ required: true, whitespace: true, message: ts('common.codeRequired') }, { pattern: CONFIG_CODE_PATTERN, message: ts('common.codeInvalid') }]}
    >
      <Input autoComplete="off" style={{ fontFamily: 'monospace' }} />
    </Form.Item>
  );
}
