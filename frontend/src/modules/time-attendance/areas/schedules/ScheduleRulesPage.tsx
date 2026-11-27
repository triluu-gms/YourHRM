import { useState } from 'react';
import { Button, Col, DatePicker, Form, Input, InputNumber, Modal, Row, Select, Space, Typography } from 'antd';
import { PlusOutlined, SyncOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, ProblemAlert, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatValidity, localizedName } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import { LegalEntitySelect, LocationSelect, OrgUnitSelect } from '../../common';
import type { ConfigStatus, ScheduleRule } from './api';
import { useEmployeeCategories, useJobTitles, usePatterns, useRegenerateRules, useRules, useSaveRule } from './queries';
import { clean, fromExclusiveEnd, iso, toExclusiveEnd, useLookupLabels } from './helpers';
import { CodeItem } from './ShiftsPage';
import { SCHEDULES_NS } from './i18n';

/** Rule-based schedule assignment (BR-ATT-015, US-ATT-010): the first matching rule by priority gives the pattern. */
export function ScheduleRulesPage() {
  const { t, i18n } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const canUpdate = usePermission(P.SCHEDULE_CONFIG_UPDATE);
  const query = useRules();
  const patterns = usePatterns();
  const jobTitles = useJobTitles();
  const labels = useLookupLabels();
  const [editing, setEditing] = useState<ScheduleRule | 'new' | null>(null);
  const [regenerating, setRegenerating] = useState(false);
  const rows = [...(query.data ?? [])].sort((a, b) => a.priority - b.priority || a.code.localeCompare(b.code));
  const patternCode = (id: UUID) => patterns.data?.find((p) => p.id === id)?.code ?? id;
  const jobTitle = (id?: UUID | null) => {
    if (!id) return '';
    const j = jobTitles.data?.find((x) => x.id === id);
    return j ? `${j.code} – ${localizedName(j, i18n.language)}` : id;
  };

  const criteria = (r: ScheduleRule) => {
    const parts = [
      r.legalEntityId ? labels.legalEntity(r.legalEntityId) : null,
      r.orgUnitId ? `${ts('rule.field.orgUnitId')}: ${labels.orgUnit(r.orgUnitId)}` : null,
      r.locationId ? `${ts('rule.field.locationId')}: ${labels.location(r.locationId)}` : null,
      r.jobTitleId ? `${ts('rule.field.jobTitleId')}: ${jobTitle(r.jobTitleId)}` : null,
      r.employeeCategoryCode ? `${ts('rule.field.employeeCategoryCode')}: ${r.employeeCategoryCode}` : null,
      r.workingRegimeCode ? `${ts('rule.field.workingRegimeCode')}: ${r.workingRegimeCode}` : null,
    ].filter(Boolean);
    return parts.length ? (
      <Space direction="vertical" size={0}>
        {parts.map((p, i) => (
          <span key={i}>{p}</span>
        ))}
      </Space>
    ) : (
      <Typography.Text type="secondary">{ts('rule.matchesAll')}</Typography.Text>
    );
  };

  return (
    <div>
      <PageHeader
        title={ts('rule.title')}
        subTitle={ts('rule.subtitle')}
        extra={
          <PermissionGate anyOf={P.SCHEDULE_CONFIG_UPDATE}>
            <Button icon={<SyncOutlined />} onClick={() => setRegenerating(true)}>
              {ts('rule.regenerate')}
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>
              {ts('rule.create')}
            </Button>
          </PermissionGate>
        }
      />
      <DataTable<ScheduleRule>
        data={rows}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: ts('rule.empty') }}
        columns={[
          { title: ts('rule.field.priority'), dataIndex: 'priority' },
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: ts('rule.field.criteria'), key: 'criteria', render: (_, r) => criteria(r) },
          { title: ts('rule.field.patternId'), dataIndex: 'patternId', render: (id: UUID) => <code>{patternCode(id)}</code> },
          { title: ts('common.validity'), key: 'validity', render: (_, r) => formatValidity(r.validFrom, r.validTo, t('common.openEnded')) },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, r: ScheduleRule) => (
                    <Button size="small" onClick={() => setEditing(r)}>
                      {t('action.edit')}
                    </Button>
                  ),
                },
              ]
            : []),
        ]}
      />
      {editing ? <RuleDrawer rule={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} /> : null}
      {regenerating ? <RegenerateModal onClose={() => setRegenerating(false)} /> : null}
    </div>
  );
}

interface RuleValues {
  code?: string;
  priority?: number | null;
  legalEntityId?: UUID;
  orgUnitId?: UUID;
  locationId?: UUID;
  jobTitleId?: UUID;
  employeeCategoryCode?: string;
  workingRegimeCode?: string;
  patternId: UUID;
  validFrom: Dayjs;
  lastDay?: Dayjs | null;
  status?: ConfigStatus;
}

function RuleDrawer({ rule, onClose }: { rule?: ScheduleRule; onClose: () => void }) {
  const { t, i18n } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<RuleValues>();
  const save = useSaveRule();
  const patterns = usePatterns();
  const jobTitles = useJobTitles();
  const categories = useEmployeeCategories();
  const legalEntityId = Form.useWatch('legalEntityId', form);
  const editing = !!rule;

  const initialValues: Partial<RuleValues> = rule
    ? {
        priority: rule.priority,
        legalEntityId: rule.legalEntityId ?? undefined,
        orgUnitId: rule.orgUnitId ?? undefined,
        locationId: rule.locationId ?? undefined,
        jobTitleId: rule.jobTitleId ?? undefined,
        employeeCategoryCode: rule.employeeCategoryCode ?? undefined,
        workingRegimeCode: rule.workingRegimeCode ?? undefined,
        patternId: rule.patternId,
        validFrom: dayjs(rule.validFrom),
        lastDay: fromExclusiveEnd(rule.validTo),
        status: rule.status,
      }
    : { priority: 100, validFrom: dayjs().startOf('month') };

  const submit = async () => {
    let v: RuleValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const result = await save.mutateAsync({
        id: rule?.id,
        body: {
          code: editing ? rule.code : (clean(v.code)?.toUpperCase() ?? null),
          priority: v.priority ?? null,
          legalEntityId: v.legalEntityId ?? null,
          orgUnitId: v.orgUnitId ?? null,
          locationId: v.locationId ?? null,
          jobTitleId: v.jobTitleId ?? null,
          employeeCategoryCode: v.employeeCategoryCode ?? null,
          workingRegimeCode: clean(v.workingRegimeCode)?.toUpperCase() ?? null,
          patternId: v.patternId,
          validFrom: iso(v.validFrom) as string,
          validTo: toExclusiveEnd(v.lastDay),
          status: editing ? (v.status ?? null) : null,
          version: rule?.version ?? null,
        },
      });
      fb.saved(ts('rule.saved', { count: result.linesChanged }));
      onClose();
    } catch {
      // shown by the drawer
    }
  };

  const patternOptions = (patterns.data ?? [])
    .filter((p) => p.status === 'ACTIVE' || p.id === rule?.patternId)
    .map((p) => ({ value: p.id, label: `${p.code} – ${localizedName(p, i18n.language)}` }));

  return (
    <FormDrawer
      open
      onClose={onClose}
      width={680}
      title={editing ? ts('rule.editTitle', { code: rule.code }) : ts('rule.create')}
      onSubmit={() => void submit()}
      submitting={save.isPending}
      error={save.error}
    >
      <Form<RuleValues> form={form} layout="vertical" initialValues={initialValues}>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <CodeItem code={editing ? rule.code : undefined} />
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="priority" label={ts('rule.field.priority')} extra={ts('rule.priorityHint')}>
              <InputNumber min={0} max={9999} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24}>
            <Form.Item name="patternId" label={ts('rule.field.patternId')} rules={[{ required: true, message: ts('rule.patternRequired') }]}>
              <Select showSearch optionFilterProp="label" loading={patterns.isLoading} options={patternOptions} />
            </Form.Item>
          </Col>
        </Row>
        <Typography.Paragraph type="secondary">{ts('rule.criteriaHint')}</Typography.Paragraph>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item name="legalEntityId" label={t('field.legalEntity')}>
              <LegalEntitySelect
                onChange={() => {
                  form.setFieldValue('orgUnitId', undefined);
                  form.setFieldValue('locationId', undefined);
                }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="orgUnitId"
              label={ts('rule.field.orgUnitId')}
              extra={ts('rule.orgUnitHint')}
              dependencies={['legalEntityId']}
              rules={[
                ({ getFieldValue }) => ({
                  validator: (_, v) =>
                    !v || getFieldValue('legalEntityId') ? Promise.resolve() : Promise.reject(new Error(ts('rule.orgUnitNeedsLegalEntity'))),
                }),
              ]}
            >
              <OrgUnitSelect legalEntityId={legalEntityId} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="locationId" label={ts('rule.field.locationId')}>
              <LocationSelect legalEntityId={legalEntityId} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="jobTitleId" label={ts('rule.field.jobTitleId')}>
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                loading={jobTitles.isLoading}
                options={(jobTitles.data ?? []).map((j) => ({ value: j.id, label: `${j.code} – ${localizedName(j, i18n.language)}` }))}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="employeeCategoryCode" label={ts('rule.field.employeeCategoryCode')}>
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                loading={categories.isLoading}
                options={(categories.data ?? []).map((c) => ({ value: c.code, label: `${c.code} – ${localizedName(c, i18n.language)}` }))}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="workingRegimeCode" label={ts('rule.field.workingRegimeCode')} extra={ts('rule.regimeHint')} rules={[{ max: 40 }]}>
              <Input style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          </Col>
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
          {editing ? (
            <Col xs={24} md={12}>
              <Form.Item name="status" label={t('field.status')}>
                <Select options={(['ACTIVE', 'INACTIVE'] as const).map((s) => ({ value: s, label: t(`status.${s}`) }))} />
              </Form.Item>
            </Col>
          ) : null}
        </Row>
      </Form>
    </FormDrawer>
  );
}

/** Re-evaluates the rules for every assignment line of a legal entity (all when none is picked). */
function RegenerateModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const { t: ts } = useTranslation(SCHEDULES_NS);
  const fb = useFeedback();
  const regenerate = useRegenerateRules();
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const submit = async () => {
    try {
      const r = await regenerate.mutateAsync(legalEntityId ?? null);
      fb.done(ts('rule.regenerated', { count: r.linesChanged }));
      onClose();
    } catch {
      // shown in the modal
    }
  };
  return (
    <Modal
      open
      title={ts('rule.regenerateTitle')}
      onCancel={onClose}
      onOk={() => void submit()}
      okText={ts('rule.regenerate')}
      cancelText={t('action.cancel')}
      confirmLoading={regenerate.isPending}
    >
      {regenerate.error ? <ProblemAlert error={regenerate.error} style={{ marginBottom: 12 }} /> : null}
      <p>{ts('rule.regenerateHint')}</p>
      <LegalEntitySelect
        aria-label={t('field.legalEntity')}
        placeholder={ts('rule.allLegalEntities')}
        style={{ width: '100%' }}
        value={legalEntityId}
        onChange={setLegalEntityId}
      />
    </Modal>
  );
}
