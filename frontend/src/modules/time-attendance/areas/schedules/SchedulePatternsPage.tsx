import { useState } from 'react';
import { Alert, Button, Col, DatePicker, Form, Input, Row, Select, Space, Table, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import { CYCLE_LENGTHS, DAY_KINDS, type ConfigStatus, type DayKind, type PatternResult, type SchedulePattern, type Shift } from './api';
import { usePatterns, useSavePattern, useShifts } from './queries';
import { clean, hhmm, iso, WarningsAlert } from './helpers';
import { CodeItem } from './ShiftsPage';
import { SCHEDULES_NS } from './i18n';

const KIND_COLOURS: Record<DayKind, string> = { WORK: 'blue', REST: 'red', OFF: 'default' };

/** ISO weekday (1 = Monday) of cycle day `i` (the anchor is a Monday). */
const isoDow = (i: number) => (i % 7) + 1;

/** Weekly schedule patterns (BR-ATT-011/014, US-ATT-009): 7-day or alternating 14-day cycles anchored on a Monday. */
export function SchedulePatternsPage() {
  const { t, i18n } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const canUpdate = usePermission(P.SCHEDULE_CONFIG_UPDATE);
  const query = usePatterns();
  const [editing, setEditing] = useState<SchedulePattern | 'new' | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const name = (p: SchedulePattern) => (i18n.language.startsWith('en') ? p.nameEn || p.nameVi : p.nameVi);

  return (
    <div>
      <PageHeader
        title={ts('pattern.title')}
        subTitle={ts('pattern.subtitle')}
        extra={
          <PermissionGate anyOf={P.SCHEDULE_CONFIG_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>
              {ts('pattern.create')}
            </Button>
          </PermissionGate>
        }
      />
      <WarningsAlert warnings={warnings} onClose={() => setWarnings([])} />
      <DataTable<SchedulePattern>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: ts('pattern.empty') }}
        expandable={{ expandedRowRender: (p) => <PatternDays pattern={p} /> }}
        columns={[
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: t('field.name'), key: 'name', render: (_, p) => name(p) },
          { title: ts('pattern.field.cycleLengthDays'), dataIndex: 'cycleLengthDays', render: (n: number) => ts('pattern.cycle', { n }) },
          { title: ts('pattern.field.anchorDate'), dataIndex: 'anchorDate', render: (d: string) => formatDate(d) },
          {
            title: ts('pattern.field.weeklyRestDays'),
            dataIndex: 'weeklyRestDays',
            render: (days: number[]) => days.map((d) => ts(`weekday.${d}`)).join(', '),
          },
          { title: ts('pattern.field.stdHoursWeek'), dataIndex: 'stdHoursWeek' },
          { title: ts('pattern.field.workDaysWeek'), dataIndex: 'workDaysWeek' },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, p: SchedulePattern) => (
                    <Button size="small" onClick={() => setEditing(p)}>
                      {t('action.edit')}
                    </Button>
                  ),
                },
              ]
            : []),
        ]}
      />
      {editing ? (
        <PatternDrawer
          pattern={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={(r) => setWarnings(r.warnings)}
        />
      ) : null}
    </div>
  );
}

function PatternDays({ pattern }: { pattern: SchedulePattern }) {
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const weeks = Array.from({ length: pattern.cycleLengthDays / 7 }, (_, w) => pattern.days.filter((d) => Math.floor(d.dayIndex / 7) === w));
  return (
    <Space direction="vertical">
      {weeks.map((days, w) => (
        <Space key={w} wrap>
          {pattern.cycleLengthDays > 7 ? <Typography.Text type="secondary">{ts('pattern.week', { n: w + 1 })}</Typography.Text> : null}
          {days.map((d) => (
            <Tag key={d.dayIndex} color={KIND_COLOURS[d.dayKind]}>
              {ts(`weekday.${isoDow(d.dayIndex)}`)}: {d.dayKind === 'WORK' ? d.shiftCode : ts(`dayKind.${d.dayKind}`)}
            </Tag>
          ))}
        </Space>
      ))}
    </Space>
  );
}

interface DayValues {
  dayKind: DayKind;
  shiftId?: UUID | null;
}

interface PatternValues {
  code?: string;
  nameVi: string;
  nameEn?: string;
  cycleLengthDays: number;
  anchorDate: Dayjs;
  days: DayValues[];
  status?: ConfigStatus;
}

function firstMondayOfYear(): Dayjs {
  const jan1 = dayjs().startOf('year');
  return jan1.add((8 - jan1.day()) % 7, 'day');
}

/** A 5-day week: Monday–Friday WORK with the first active shift, Saturday OFF, Sunday REST. */
function defaultDays(cycle: number, shiftId?: UUID): DayValues[] {
  return Array.from({ length: cycle }, (_, i) => {
    const dow = isoDow(i);
    if (dow === 7) return { dayKind: 'REST' as DayKind, shiftId: null };
    if (dow === 6) return { dayKind: 'OFF' as DayKind, shiftId: null };
    return { dayKind: 'WORK' as DayKind, shiftId: shiftId ?? null };
  });
}

export function PatternDrawer({
  pattern,
  onClose,
  onSaved,
}: {
  pattern?: SchedulePattern;
  onClose: () => void;
  onSaved: (r: PatternResult) => void;
}) {
  const { t } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<PatternValues>();
  const save = useSavePattern();
  const shifts = useShifts();
  const activeShifts = (shifts.data ?? []).filter((s) => s.status === 'ACTIVE' || pattern?.days.some((d) => d.shiftId === s.id));
  const days = Form.useWatch('days', form) as DayValues[] | undefined;
  const editing = !!pattern;

  const initialValues: Partial<PatternValues> = pattern
    ? {
        nameVi: pattern.nameVi,
        nameEn: pattern.nameEn ?? undefined,
        cycleLengthDays: pattern.cycleLengthDays,
        anchorDate: dayjs(pattern.anchorDate),
        days: [...pattern.days].sort((a, b) => a.dayIndex - b.dayIndex).map((d) => ({ dayKind: d.dayKind, shiftId: d.shiftId ?? null })),
        status: pattern.status,
      }
    : { cycleLengthDays: 7, anchorDate: firstMondayOfYear(), days: defaultDays(7) };

  const onCycleChange = (cycle: number) => {
    const current = (form.getFieldValue('days') as DayValues[] | undefined) ?? [];
    const next = Array.from({ length: cycle }, (_, i) => current[i] ?? current[i % 7] ?? defaultDays(cycle)[i]);
    form.setFieldValue('days', next);
  };

  const submit = async () => {
    let v: PatternValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const result = await save.mutateAsync({
        id: pattern?.id,
        body: {
          code: editing ? pattern.code : (clean(v.code)?.toUpperCase() ?? null),
          nameVi: v.nameVi.trim(),
          nameEn: clean(v.nameEn),
          cycleLengthDays: v.cycleLengthDays,
          anchorDate: iso(v.anchorDate) as string,
          days: v.days.map((d, i) => ({ dayIndex: i, dayKind: d.dayKind, shiftId: d.dayKind === 'WORK' ? (d.shiftId ?? null) : null })),
          status: editing ? (v.status ?? null) : null,
          version: pattern?.version ?? null,
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

  const shiftLabel = (s: Shift) => `${s.code} (${hhmm(s.startTime)}–${hhmm(s.endTime)})`;
  const rows = (days ?? initialValues.days ?? []).map((d, i) => ({ ...d, index: i }));

  return (
    <FormDrawer
      open
      onClose={onClose}
      width={720}
      title={editing ? ts('pattern.editTitle', { code: pattern.code }) : ts('pattern.create')}
      onSubmit={() => void submit()}
      submitting={save.isPending}
      error={save.error}
    >
      {editing ? <Alert type="info" showIcon style={{ marginBottom: 12 }} message={ts('pattern.frozenHint')} /> : null}
      <Form<PatternValues> form={form} layout="vertical" initialValues={initialValues}>
        <Row gutter={16}>
          <Col xs={24} md={8}>
            <CodeItem code={editing ? pattern.code : undefined} />
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
          <Col xs={24} md={8}>
            <Form.Item name="cycleLengthDays" label={ts('pattern.field.cycleLengthDays')} extra={ts('pattern.cycleHint')}>
              <Select<number> onChange={onCycleChange} options={CYCLE_LENGTHS.map((n) => ({ value: n, label: ts('pattern.cycle', { n }) }))} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="anchorDate"
              label={ts('pattern.field.anchorDate')}
              extra={ts('pattern.anchorHint')}
              rules={[
                { required: true, message: ts('pattern.anchorRequired') },
                { validator: (_, d?: Dayjs) => (!d || d.day() === 1 ? Promise.resolve() : Promise.reject(new Error(ts('pattern.anchorMonday')))) },
              ]}
            >
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} disabledDate={(d) => d.day() !== 1} />
            </Form.Item>
          </Col>
          {editing ? (
            <Col xs={24} md={8}>
              <Form.Item name="status" label={t('field.status')} extra={ts('common.statusHint')}>
                <Select options={(['ACTIVE', 'INACTIVE'] as const).map((s) => ({ value: s, label: t(`status.${s}`) }))} />
              </Form.Item>
            </Col>
          ) : null}
        </Row>
        <Typography.Paragraph type="secondary">{ts('pattern.daysHint')}</Typography.Paragraph>
        <Table
          size="small"
          rowKey="index"
          pagination={false}
          dataSource={rows}
          columns={[
            {
              title: ts('pattern.field.day'),
              key: 'day',
              render: (_, r) =>
                (rows.length > 7 ? `${ts('pattern.week', { n: Math.floor(r.index / 7) + 1 })} · ` : '') + ts(`weekdayLong.${isoDow(r.index)}`),
            },
            {
              title: ts('pattern.field.dayKind'),
              key: 'kind',
              render: (_, r) => (
                <Form.Item name={['days', r.index, 'dayKind']} style={{ marginBottom: 0 }} rules={[{ required: true }]}>
                  <Select<DayKind>
                    aria-label={`${ts('pattern.field.dayKind')} ${r.index}`}
                    style={{ width: 160 }}
                    options={DAY_KINDS.map((k) => ({ value: k, label: ts(`dayKind.${k}`) }))}
                  />
                </Form.Item>
              ),
            },
            {
              title: ts('pattern.field.shift'),
              key: 'shift',
              render: (_, r) =>
                r.dayKind === 'WORK' ? (
                  <Form.Item
                    name={['days', r.index, 'shiftId']}
                    style={{ marginBottom: 0 }}
                    rules={[{ required: true, message: ts('pattern.shiftRequired') }]}
                  >
                    <Select<UUID>
                      aria-label={`${ts('pattern.field.shift')} ${r.index}`}
                      style={{ width: 240 }}
                      loading={shifts.isLoading}
                      options={activeShifts.map((s) => ({ value: s.id, label: shiftLabel(s) }))}
                    />
                  </Form.Item>
                ) : null,
            },
          ]}
        />
      </Form>
    </FormDrawer>
  );
}
