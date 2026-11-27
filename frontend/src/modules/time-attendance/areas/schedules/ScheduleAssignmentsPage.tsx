import { useState } from 'react';
import { AutoComplete, Button, Checkbox, Col, DatePicker, Empty, Form, Input, InputNumber, Row, Select, Space, Tabs, Tag, Typography } from 'antd';
import { PlusOutlined, SyncOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { ConfirmButton, DataTable, DescriptionList, FormDrawer, PageHeader, ProblemAlert, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatValidity, ISO_DATE, localizedName } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import { EmployeeSelect } from '../../common';
import {
  STD_DAY_METHODS,
  type PlannedDay,
  type ScheduleAssignment,
  type StandardDaysPreviewParams,
  type StandardDaysResult,
  type StdDayMethod,
  type StdSegment,
} from './api';
import {
  useAssignmentActions,
  usePatterns,
  usePeriods,
  useScheduleAssignments,
  useStandardDays,
  useStandardDaysPreview,
  useWorkPlan,
} from './queries';
import { iso, toExclusiveEnd, useLookupLabels } from './helpers';
import { SCHEDULES_NS } from './i18n';

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const KIND_COLOURS: Record<string, string> = { WORK: 'blue', REST: 'red', OFF: 'default' };
const SOURCE_COLOURS: Record<string, string> = { RULE: 'geekblue', MANUAL: 'gold', IMPORT: 'cyan', OPENING_LOAD: 'purple' };

export interface AssignmentsSearch {
  employmentId?: UUID;
  assignmentKey?: UUID;
}

/**
 * Schedules of one employee (US-ATT-010..014): rule-based and manual assignments, the planned days with holidays and
 * substitute days, and STD_DAYS / STD_HOURS per period or as a preview. The employee is a search param.
 */
export function ScheduleAssignmentsPage({
  employmentId,
  assignmentKey,
  onChange,
}: AssignmentsSearch & { onChange: (next: AssignmentsSearch) => void }) {
  const { t: ts } = useTranslation(SCHEDULES_NS);
  return (
    <div>
      <PageHeader title={ts('assignment.title')} subTitle={ts('assignment.subtitle')}>
        <Space wrap>
          <Typography.Text>{ts('common.employee')}</Typography.Text>
          <EmployeeSelect
            aria-label={ts('common.employee')}
            style={{ width: 360 }}
            value={employmentId}
            onChange={(v) => onChange({ employmentId: v ?? undefined })}
          />
        </Space>
      </PageHeader>
      {!employmentId ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={ts('common.pickEmployee')} />
      ) : (
        <Tabs
          items={[
            {
              key: 'assignments',
              label: ts('assignment.tab'),
              children: <AssignmentsTab key={employmentId} employmentId={employmentId} prefillKey={assignmentKey} />,
            },
            { key: 'plan', label: ts('plan.tab'), children: <WorkPlanTab employmentId={employmentId} /> },
            { key: 'std', label: ts('std.tab'), children: <StandardDaysTab employmentId={employmentId} /> },
          ]}
        />
      )}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ assignments

function AssignmentsTab({ employmentId, prefillKey }: { employmentId: UUID; prefillKey?: UUID }) {
  const { t } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const fb = useFeedback();
  const canUpdate = usePermission(P.SCHEDULE_ASSIGNMENT_UPDATE);
  const [includeCancelled, setIncludeCancelled] = useState(false);
  const [creating, setCreating] = useState(!!prefillKey && canUpdate);
  const query = useScheduleAssignments(employmentId, includeCancelled);
  const actions = useAssignmentActions(employmentId);
  const rows = [...(query.data ?? [])].sort((a, b) => (a.validFrom < b.validFrom ? 1 : -1));
  const knownKeys = Array.from(new Set([...(prefillKey ? [prefillKey] : []), ...rows.map((r) => r.assignmentKey)]));

  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <Checkbox checked={includeCancelled} onChange={(e) => setIncludeCancelled(e.target.checked)}>
          {ts('assignment.includeCancelled')}
        </Checkbox>
        <PermissionGate anyOf={P.SCHEDULE_ASSIGNMENT_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            {ts('assignment.create')}
          </Button>
          <ConfirmButton
            icon={<SyncOutlined />}
            title={ts('assignment.regenerateConfirm')}
            loading={actions.regenerate.isPending}
            onConfirm={async () => {
              try {
                await actions.regenerate.mutateAsync();
                fb.done(ts('assignment.regenerated'));
              } catch (e) {
                fb.failed(e);
              }
            }}
          >
            {ts('assignment.regenerate')}
          </ConfirmButton>
        </PermissionGate>
      </Space>
      <DataTable<ScheduleAssignment>
        data={rows}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: ts('assignment.empty') }}
        columns={[
          { title: ts('assignment.field.patternCode'), dataIndex: 'patternCode', render: (c?: string | null, r?: ScheduleAssignment) => <code>{c ?? r?.patternId}</code> },
          { title: ts('common.validity'), key: 'validity', render: (_, r) => formatValidity(r.validFrom, r.validTo, t('common.openEnded')) },
          { title: ts('assignment.field.source'), dataIndex: 'source', render: (s: string) => <Tag color={SOURCE_COLOURS[s]}>{ts(`source.${s}`, { defaultValue: s })}</Tag> },
          { title: ts('assignment.field.assignmentKey'), dataIndex: 'assignmentKey', render: (k: string) => <Typography.Text code copyable={{ text: k }}>{k.slice(0, 8)}</Typography.Text> },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, r: ScheduleAssignment) =>
                    r.status === 'ACTIVE' && r.source !== 'RULE' ? (
                      <ConfirmButton
                        size="small"
                        danger
                        title={ts('assignment.cancelConfirm')}
                        description={ts('assignment.cancelHint')}
                        onConfirm={async () => {
                          try {
                            await actions.cancel.mutateAsync(r.id);
                            fb.done(ts('assignment.cancelled'));
                          } catch (e) {
                            fb.failed(e);
                          }
                        }}
                      >
                        {ts('assignment.cancel')}
                      </ConfirmButton>
                    ) : null,
                },
              ]
            : []),
        ]}
      />
      {creating ? (
        <ManualAssignmentDrawer employmentId={employmentId} knownKeys={knownKeys} prefillKey={prefillKey} onClose={() => setCreating(false)} />
      ) : null}
    </div>
  );
}

interface ManualValues {
  assignmentKey: UUID;
  patternId: UUID;
  validFrom: Dayjs;
  lastDay?: Dayjs | null;
}

function ManualAssignmentDrawer({
  employmentId,
  knownKeys,
  prefillKey,
  onClose,
}: {
  employmentId: UUID;
  knownKeys: UUID[];
  prefillKey?: UUID;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<ManualValues>();
  const { create } = useAssignmentActions(employmentId);
  const patterns = usePatterns();

  const submit = async () => {
    let v: ManualValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await create.mutateAsync({
        assignmentKey: v.assignmentKey.trim(),
        patternId: v.patternId,
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
    <FormDrawer open onClose={onClose} title={ts('assignment.create')} onSubmit={() => void submit()} submitting={create.isPending} error={create.error}>
      <Typography.Paragraph type="secondary">{ts('assignment.manualHint')}</Typography.Paragraph>
      <Form<ManualValues>
        form={form}
        layout="vertical"
        initialValues={{ assignmentKey: prefillKey ?? (knownKeys.length === 1 ? knownKeys[0] : undefined), validFrom: dayjs() }}
      >
        <Form.Item
          name="assignmentKey"
          label={ts('assignment.field.assignmentKey')}
          extra={ts('assignment.assignmentKeyHint')}
          rules={[
            { required: true, message: ts('assignment.assignmentKeyRequired') },
            { pattern: UUID_RE, message: ts('assignment.assignmentKeyInvalid') },
          ]}
        >
          <AutoComplete options={knownKeys.map((k) => ({ value: k }))} style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="patternId" label={ts('assignment.field.patternCode')} rules={[{ required: true, message: ts('rule.patternRequired') }]}>
          <Select
            showSearch
            optionFilterProp="label"
            loading={patterns.isLoading}
            options={(patterns.data ?? [])
              .filter((p) => p.status === 'ACTIVE')
              .map((p) => ({ value: p.id, label: `${p.code} – ${localizedName(p, i18n.language)}` }))}
          />
        </Form.Item>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item name="validFrom" label={t('field.validFrom')} rules={[{ required: true, message: ts('common.validFromRequired') }]}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="lastDay" label={ts('common.lastDay')} extra={ts('common.lastDayHint')}>
              <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ work plan

function WorkPlanTab({ employmentId }: { employmentId: UUID }) {
  const { t, i18n } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const labels = useLookupLabels();
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().startOf('month'), dayjs().endOf('month')]);
  const plan = useWorkPlan(employmentId, range[0].format(ISO_DATE), range[1].format(ISO_DATE));
  const days = plan.data ?? [];
  const working = days.filter((d) => d.workingDay ?? (d.scheduled && d.dayKind === 'WORK' && !d.holiday && !d.substituteForDate));
  const hours = working.reduce((s, d) => s + Number(d.stdHours ?? 0), 0);

  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <DatePicker.RangePicker
          format={DATE_FORMAT}
          allowClear={false}
          value={range}
          onChange={(v) => v && v[0] && v[1] && setRange([v[0], v[1]])}
        />
        {plan.data ? (
          <Typography.Text type="secondary">{ts('plan.summary', { days: working.length, hours: Math.round(hours * 100) / 100 })}</Typography.Text>
        ) : null}
      </Space>
      <DataTable<PlannedDay>
        rowKey="date"
        data={days}
        isLoading={plan.isLoading}
        error={plan.error}
        columns={[
          { title: ts('plan.field.date'), dataIndex: 'date', render: (d: string) => formatDate(d) },
          { title: ts('plan.field.weekday'), key: 'dow', render: (_, d) => ts(`weekday.${dayjs(d.date).day() === 0 ? 7 : dayjs(d.date).day()}`) },
          {
            title: ts('plan.field.dayKind'),
            key: 'kind',
            render: (_, d) =>
              !d.scheduled ? (
                <Tag color="orange">{ts('plan.unscheduled')}</Tag>
              ) : d.dayKind ? (
                <Tag color={KIND_COLOURS[d.dayKind]}>{ts(`dayKind.${d.dayKind}`)}</Tag>
              ) : null,
          },
          { title: ts('plan.field.shift'), dataIndex: 'shiftCode', render: (c?: string | null) => (c ? <code>{c}</code> : null) },
          { title: ts('plan.field.stdHours'), dataIndex: 'stdHours' },
          { title: ts('plan.field.dayValue'), dataIndex: 'dayValue' },
          {
            title: ts('plan.field.holiday'),
            key: 'holiday',
            render: (_, d) =>
              d.holiday ? (
                <Tag color="red">{localizedName(d.holiday, i18n.language)}</Tag>
              ) : d.substituteForDate ? (
                <Tag color="volcano">{ts('plan.substituteFor', { date: formatDate(d.substituteForDate) })}</Tag>
              ) : null,
          },
          { title: ts('plan.field.pattern'), dataIndex: 'patternCode', render: (c?: string | null) => (c ? <code>{c}</code> : null) },
          { title: t('field.location'), dataIndex: 'locationId', render: (id?: string | null) => labels.location(id) },
        ]}
      />
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ standard days

function StandardDaysTab({ employmentId }: { employmentId: UUID }) {
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const labels = useLookupLabels();
  const canSeePeriods = usePermission(P.PERIOD_VIEW);
  const periods = usePeriods(undefined, canSeePeriods);
  const [periodId, setPeriodId] = useState<UUID | undefined>();
  const byPeriod = useStandardDays(employmentId, periodId && UUID_RE.test(periodId) ? periodId : undefined);
  const [form] = Form.useForm<{ range: [Dayjs, Dayjs]; method: StdDayMethod; value?: number | null }>();
  const method = Form.useWatch('method', form);
  const [params, setParams] = useState<StandardDaysPreviewParams | null>(null);
  const preview = useStandardDaysPreview(employmentId, params);

  const compute = async () => {
    let v: { range: [Dayjs, Dayjs]; method: StdDayMethod; value?: number | null };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    setParams({
      from: v.range[0].format(ISO_DATE),
      to: v.range[1].format(ISO_DATE),
      method: v.method,
      value: v.method.startsWith('FIXED_') ? (v.value ?? null) : null,
    });
  };

  const sortedPeriods = [...(periods.data ?? [])].sort((a, b) => (a.periodStart < b.periodStart ? 1 : -1));

  return (
    <div>
      <Typography.Title level={5}>{ts('std.byPeriod')}</Typography.Title>
      <Typography.Paragraph type="secondary">{ts('std.byPeriodHint')}</Typography.Paragraph>
      {canSeePeriods ? (
        <Select<UUID>
          aria-label={ts('std.period')}
          placeholder={ts('std.period')}
          style={{ width: 420, marginBottom: 12 }}
          showSearch
          optionFilterProp="label"
          loading={periods.isLoading}
          value={periodId}
          onChange={setPeriodId}
          options={sortedPeriods.map((p) => ({
            value: p.id,
            label: `${formatDate(p.periodStart)} – ${formatDate(p.periodEnd)} · ${labels.legalEntity(p.legalEntityId)}`,
          }))}
        />
      ) : (
        <Input
          aria-label={ts('std.periodId')}
          placeholder={ts('std.periodId')}
          style={{ width: 420, marginBottom: 12, fontFamily: 'monospace' }}
          value={periodId}
          onChange={(e) => setPeriodId(e.target.value.trim() || undefined)}
        />
      )}
      {byPeriod.error ? <ProblemAlert error={byPeriod.error} style={{ marginBottom: 12 }} /> : null}
      {byPeriod.data ? <StdResult result={byPeriod.data} /> : null}

      <Typography.Title level={5} style={{ marginTop: 24 }}>
        {ts('std.preview')}
      </Typography.Title>
      <Typography.Paragraph type="secondary">{ts('std.previewHint')}</Typography.Paragraph>
      <Form
        form={form}
        layout="inline"
        initialValues={{ range: [dayjs().startOf('month'), dayjs().endOf('month')], method: 'SCHEDULE_DAYS' }}
        style={{ marginBottom: 12, rowGap: 8 }}
      >
        <Form.Item name="range" rules={[{ required: true }]}>
          <DatePicker.RangePicker format={DATE_FORMAT} allowClear={false} />
        </Form.Item>
        <Form.Item name="method" label={ts('std.method')}>
          <Select<StdDayMethod> style={{ width: 220 }} options={STD_DAY_METHODS.map((m) => ({ value: m, label: ts(`method.${m}`) }))} />
        </Form.Item>
        {method?.startsWith('FIXED_') ? (
          <Form.Item name="value" label={ts('std.value')} rules={[{ required: true, message: ts('std.valueRequired') }]}>
            <InputNumber min={0.01} step={1} style={{ width: 120 }} />
          </Form.Item>
        ) : null}
        <Form.Item>
          <Button onClick={() => void compute()} loading={preview.isFetching}>
            {ts('std.compute')}
          </Button>
        </Form.Item>
      </Form>
      {preview.error ? <ProblemAlert error={preview.error} style={{ marginBottom: 12 }} /> : null}
      {params && preview.data ? <StdResult result={preview.data} /> : null}
    </div>
  );
}

function StdResult({ result }: { result: StandardDaysResult }) {
  const { t: ts } = useTranslation(SCHEDULES_NS);
  return (
    <>
      <DescriptionList
        style={{ marginBottom: 12 }}
        items={[
          { label: ts('std.method'), value: ts(`method.${result.method}`, { defaultValue: result.method }) },
          { label: ts('std.value'), value: result.methodValue, hideEmpty: true },
          { label: ts('std.periodStdDays'), value: result.periodStdDays },
          { label: ts('std.periodStdHours'), value: result.periodStdHours },
        ]}
      />
      {result.segments.length > 1 ? (
        <DataTable<StdSegment>
          rowKey={(s) => `${s.segmentId ?? ''}|${s.from}`}
          data={result.segments}
          columns={[
            { title: ts('std.segment'), key: 'range', render: (_, s) => `${formatDate(s.from)} – ${formatDate(s.to)}` },
            { title: ts('std.periodStdDays'), dataIndex: 'stdDays' },
            { title: ts('std.periodStdHours'), dataIndex: 'stdHours' },
          ]}
        />
      ) : null}
    </>
  );
}
