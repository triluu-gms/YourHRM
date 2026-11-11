import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, DatePicker, Drawer, Form, Input, Select, Space, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DataTable, DescriptionList, FormDrawer, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime, formatValidity, localizedName, today } from '@/shared/format';
import {
  applyFieldErrors,
  GradeName,
  JobTitleName,
  LegalEntityName,
  LocationName,
  OrgUnitName,
  toIsoDate,
  type ProfileTabProps,
} from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import type { AssignmentChangeResult, AssignmentView } from './api';
import { AssignmentFields, attributesFrom, toAttributes, type AttributesFormValues } from './AssignmentFields';
import {
  useAssignmentHistory,
  useAssignmentsAsOf,
  useCancelAssignment,
  useChangeReasons,
  useCorrectAssignment,
  useRequestAssignmentChange,
} from './queries';
import './i18n';

const NS = 'people-employment';

/**
 * Date-effective assignments (EP-PPL-04): as of the profile date or the full bitemporal history; change from a
 * date through approval (US-PPL-036/040), correction of a version (US-PPL-037), cancellation of a future change
 * (US-PPL-038).
 */
export function AssignmentsTab({ employmentId, profile }: ProfileTabProps) {
  const { t: te } = useTranslation(NS);
  const { t: tp } = useTranslation('people');
  const { t } = useTranslation();
  const [showHistory, setShowHistory] = useState(false);
  const [detail, setDetail] = useState<AssignmentView | null>(null);
  const [changing, setChanging] = useState(false);
  const [correcting, setCorrecting] = useState<AssignmentView | null>(null);
  const [cancelling, setCancelling] = useState<AssignmentView | null>(null);
  const [lastResult, setLastResult] = useState<AssignmentChangeResult | null>(null);
  const current = useAssignmentsAsOf(employmentId, profile.asOf);
  const history = useAssignmentHistory(employmentId, showHistory);
  const query = showHistory ? history : current;
  const legalEntityId = profile.employment.legalEntityId ?? '';
  const primary = useMemo(
    () => (current.data ?? []).find((a) => a.assignmentType === 'PRIMARY') ?? current.data?.[0] ?? null,
    [current.data],
  );
  const todayIso = today();
  const isCurrentKnowledge = (a: AssignmentView) => !a.knownTo && !a.supersededBy;

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {lastResult ? <ChangeResultAlert result={lastResult} onClose={() => setLastResult(null)} /> : null}
      <Space wrap style={{ justifyContent: 'space-between', width: '100%' }}>
        <Typography.Text strong>
          {showHistory ? te('assignments.history') : te('assignments.current', { date: formatDate(profile.asOf) })}
        </Typography.Text>
        <Space wrap>
          <Checkbox checked={showHistory} onChange={(e) => setShowHistory(e.target.checked)}>
            {te('assignments.showHistory')}
          </Checkbox>
          <PermissionGate anyOf={P.ASSIGNMENT_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setChanging(true)}>
              {te('assignments.change')}
            </Button>
          </PermissionGate>
        </Space>
      </Space>

      <DataTable<AssignmentView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: te('assignments.empty') }}
        rowClassName={(a) => (isCurrentKnowledge(a) ? '' : 'ant-table-row-disabled')}
        onRow={(a) => ({ style: { cursor: 'pointer', opacity: isCurrentKnowledge(a) ? 1 : 0.55 }, onClick: () => setDetail(a) })}
        columns={[
          { title: te('assignments.validity'), key: 'v', render: (_, a) => formatValidity(a.validFrom, a.validTo, t('common.openEnded')) },
          { title: te('assignments.type'), dataIndex: 'assignmentType', render: (x: string) => <Tag>{x}</Tag> },
          { title: tp('common.orgUnit'), dataIndex: 'orgUnitId', render: (id: string) => <OrgUnitName id={id} /> },
          { title: tp('common.jobTitle'), dataIndex: 'jobTitleId', render: (id: string | null) => <JobTitleName id={id} /> },
          { title: tp('common.grade'), dataIndex: 'gradeId', render: (id: string | null) => <GradeName id={id} /> },
          { title: tp('common.location'), dataIndex: 'locationId', render: (id: string | null) => <LocationName id={id} /> },
          { title: tp('common.fte'), dataIndex: 'fte' },
          {
            title: te('assignments.reason'),
            key: 'reason',
            render: (_, a) => [a.actionCode, a.reasonCode].filter(Boolean).join(' · '),
          },
          {
            title: te('assignments.decision'),
            dataIndex: 'decisionNo',
            render: (no: string | null, a) =>
              a.decisionId ? (
                <Link to="/people/decisions/$decisionId" params={{ decisionId: a.decisionId }} onClick={(e) => e.stopPropagation()}>
                  {no ?? a.decisionId}
                </Link>
              ) : null,
          },
          ...(showHistory
            ? [
                {
                  title: te('assignments.known'),
                  key: 'known',
                  render: (_: unknown, a: AssignmentView) => (
                    <Space size={4} wrap>
                      {formatDateTime(a.knownFrom)}
                      {a.knownTo ? <Tag>{te('assignments.superseded')}</Tag> : null}
                      {a.correctionReason ? <Tag color="orange">{te('assignments.corrected', { reason: a.correctionReason })}</Tag> : null}
                    </Space>
                  ),
                },
              ]
            : []),
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, a) =>
              isCurrentKnowledge(a) ? (
                <PermissionGate anyOf={P.ASSIGNMENT_UPDATE}>
                  <Space size={0} onClick={(e) => e.stopPropagation()}>
                    <Button type="link" size="small" onClick={() => setCorrecting(a)}>
                      {te('assignments.correct')}
                    </Button>
                    {a.validFrom > todayIso ? (
                      <Button type="link" size="small" danger onClick={() => setCancelling(a)}>
                        {te('assignments.cancel')}
                      </Button>
                    ) : null}
                  </Space>
                </PermissionGate>
              ) : null,
          },
        ]}
      />

      <AssignmentDetailDrawer assignment={detail} onClose={() => setDetail(null)} />
      <ChangeAssignmentDrawer
        open={changing}
        onClose={() => setChanging(false)}
        employmentId={employmentId}
        legalEntityId={legalEntityId}
        base={primary}
        onDone={setLastResult}
      />
      <CorrectAssignmentDrawer
        assignment={correcting}
        onClose={() => setCorrecting(null)}
        employmentId={employmentId}
        legalEntityId={legalEntityId}
      />
      <CancelAssignmentDrawer assignment={cancelling} onClose={() => setCancelling(null)} employmentId={employmentId} />
    </Space>
  );
}

function ChangeResultAlert({ result, onClose }: { result: AssignmentChangeResult; onClose: () => void }) {
  const { t: te } = useTranslation(NS);
  return (
    <Alert
      closable
      onClose={onClose}
      showIcon
      type={result.warnings.length > 0 ? 'warning' : result.status === 'PENDING_APPROVAL' ? 'info' : 'success'}
      message={
        <Space wrap>
          {te(`assignments.status.${result.status}`, { defaultValue: result.status })}
          {result.decisionId ? (
            <Link to="/people/decisions/$decisionId" params={{ decisionId: result.decisionId }}>
              {te('assignments.decisionIssued', { no: result.decisionNo ?? '' })}
            </Link>
          ) : null}
        </Space>
      }
      description={
        result.warnings.length > 0 ? (
          <>
            <Typography.Text strong>{te('assignments.warnings')}</Typography.Text>
            <ul style={{ margin: 0, paddingLeft: 16 }}>
              {result.warnings.map((w) => (
                <li key={w.code}>{te(`errors.${w.code}`, { defaultValue: w.message })}</li>
              ))}
            </ul>
          </>
        ) : undefined
      }
    />
  );
}

function AssignmentDetailDrawer({ assignment: a, onClose }: { assignment: AssignmentView | null; onClose: () => void }) {
  const { t: te } = useTranslation(NS);
  const { t: tp } = useTranslation('people');
  const { t } = useTranslation();
  return (
    <Drawer open={a !== null} onClose={onClose} title={te('assignments.detail')} width={640} destroyOnHidden>
      {a ? (
        <DescriptionList
          column={1}
          items={[
            { label: te('assignments.validity'), value: formatValidity(a.validFrom, a.validTo, t('common.openEnded')) },
            { label: te('assignments.type'), value: a.assignmentType },
            { label: tp('common.legalEntity'), value: <LegalEntityName id={a.legalEntityId} /> },
            { label: tp('common.orgUnit'), value: <OrgUnitName id={a.orgUnitId} /> },
            { label: tp('common.jobTitle'), value: <JobTitleName id={a.jobTitleId} /> },
            { label: tp('common.grade'), value: <GradeName id={a.gradeId} /> },
            { label: tp('common.location'), value: <LocationName id={a.locationId} /> },
            {
              label: tp('common.manager'),
              value: a.managerEmploymentId ? (
                <Link to="/people/employees/$employmentId" params={{ employmentId: a.managerEmploymentId }}>
                  {a.managerEmploymentId.slice(0, 8)}…
                </Link>
              ) : null,
            },
            { label: tp('common.fte'), value: a.fte },
            { label: te('assignments.costCentre'), value: a.costCentreId },
            {
              label: te('assignments.costSplit'),
              value: a.costSplit.length > 0 ? a.costSplit.map((s) => `${s.costCentreId.slice(0, 8)}… ${s.pct}%`).join(', ') : null,
              hideEmpty: true,
            },
            { label: te('assignments.action'), value: a.actionCode },
            { label: te('assignments.reason'), value: a.reasonCode },
            { label: te('assignments.decision'), value: a.decisionNo, hideEmpty: true },
            { label: te('assignments.known'), value: `${formatDateTime(a.knownFrom)}${a.knownTo ? ` – ${formatDateTime(a.knownTo)}` : ''}` },
            { label: te('assignments.correctionReason'), value: a.correctionReason, hideEmpty: true },
            { label: te('assignments.source'), value: a.source, hideEmpty: true },
          ]}
        />
      ) : null}
    </Drawer>
  );
}

// ---------------------------------------------------------------------------------------------- change

interface ChangeValues {
  reasonCode: string;
  effectiveDate: Dayjs;
  endDate?: Dayjs | null;
  attributes: AttributesFormValues;
  noticeDate?: Dayjs | null;
  consentDocumentId?: string;
  contractAppendixId?: string;
  signatoryName?: string;
  signatoryTitle?: string;
  comment?: string;
}

function ChangeAssignmentDrawer({
  open,
  onClose,
  employmentId,
  legalEntityId,
  base,
  onDone,
}: {
  open: boolean;
  onClose: () => void;
  employmentId: string;
  legalEntityId: string;
  base: AssignmentView | null;
  onDone: (r: AssignmentChangeResult) => void;
}) {
  const { t: te } = useTranslation(NS);
  const { i18n } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<ChangeValues>();
  const reasons = useChangeReasons(open);
  const change = useRequestAssignmentChange(employmentId);
  const reasonCode = Form.useWatch('reasonCode', form);
  const reason = reasons.data?.find((r) => r.code === reasonCode);
  const temporary = reason?.assignmentType === 'TEMPORARY' || reason?.actionCode === 'TEMP_TRANSFER';

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    change.reset();
    form.setFieldsValue({ effectiveDate: dayjs().add(1, 'day'), attributes: attributesFrom(base) as AttributesFormValues });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, form]);

  const submit = async () => {
    let v: ChangeValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const r = await change.mutateAsync({
        reasonCode: v.reasonCode,
        effectiveDate: toIsoDate(v.effectiveDate) as string,
        endDate: toIsoDate(v.endDate),
        attributes: toAttributes(v.attributes),
        noticeDate: toIsoDate(v.noticeDate),
        consentDocumentId: v.consentDocumentId?.trim() || null,
        contractAppendixId: v.contractAppendixId?.trim() || null,
        signatoryName: v.signatoryName?.trim() || null,
        signatoryTitle: v.signatoryTitle?.trim() || null,
        comment: v.comment?.trim() || null,
      });
      fb.done(te(`assignments.status.${r.status}`, { defaultValue: r.status }));
      onDone(r);
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={te('assignments.changeTitle')} onSubmit={() => void submit()} submitting={change.isPending} error={change.error} width={640}>
      <Form<ChangeValues> form={form} layout="vertical">
        <Form.Item name="reasonCode" label={te('assignments.reasonCode')} rules={[{ required: true, message: te('assignments.reasonRequired') }]}>
          <Select
            showSearch
            optionFilterProp="label"
            loading={reasons.isLoading}
            options={(reasons.data ?? [])
              .filter((r) => r.active && !r.phase2)
              .map((r) => ({ value: r.code, label: `${r.code} – ${localizedName(r, i18n.language)}` }))}
          />
        </Form.Item>
        {reason && reason.problems.length > 0 ? (
          <Alert type="warning" showIcon message={te('assignments.reasonProblems', { problems: reason.problems.join(', ') })} style={{ marginBottom: 16 }} />
        ) : null}
        <Space wrap>
          <Form.Item name="effectiveDate" label={te('assignments.effectiveDate')} rules={[{ required: true }]}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="endDate" label={te('assignments.endDate')} rules={[{ required: temporary, message: te('errors.temp-transfer-end-required') }]}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
          {temporary ? (
            <Form.Item name="noticeDate" label={te('assignments.noticeDate')}>
              <DatePicker format={DATE_FORMAT} />
            </Form.Item>
          ) : null}
        </Space>
        <AssignmentFields legalEntityId={legalEntityId} employmentId={employmentId} />
        {temporary ? (
          <Form.Item name="consentDocumentId" label={te('assignments.consentDocumentId')}>
            <Input style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        ) : null}
        {reason?.documentKind === 'APPENDIX' ? (
          <Form.Item name="contractAppendixId" label={te('assignments.contractAppendixId')}>
            <Input style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        ) : null}
        <Space wrap>
          <Form.Item name="signatoryName" label={te('assignments.signatoryName')} rules={[{ max: 200 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="signatoryTitle" label={te('assignments.signatoryTitle')} rules={[{ max: 200 }]}>
            <Input />
          </Form.Item>
        </Space>
        <Form.Item name="comment" label={te('assignments.comment')} rules={[{ max: 2000 }]}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// -------------------------------------------------------------------------------------------- correction

interface CorrectValues {
  reason: string;
  attributes: AttributesFormValues;
}

function CorrectAssignmentDrawer({
  assignment,
  onClose,
  employmentId,
  legalEntityId,
}: {
  assignment: AssignmentView | null;
  onClose: () => void;
  employmentId: string;
  legalEntityId: string;
}) {
  const { t: te } = useTranslation(NS);
  const { t } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<CorrectValues>();
  const correct = useCorrectAssignment(employmentId);

  useEffect(() => {
    if (!assignment) return;
    form.resetFields();
    correct.reset();
    form.setFieldsValue({ attributes: attributesFrom(assignment) as AttributesFormValues });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [assignment, form]);

  const submit = async () => {
    if (!assignment) return;
    let v: CorrectValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await correct.mutateAsync({ assignmentId: assignment.id, body: { reason: v.reason.trim(), attributes: toAttributes(v.attributes) } });
      fb.done(te('assignments.correctedDone'));
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open={assignment !== null}
      onClose={onClose}
      title={te('assignments.correctTitle')}
      onSubmit={() => void submit()}
      submitting={correct.isPending}
      error={correct.error}
      width={640}
    >
      {assignment ? (
        <Alert
          type="info"
          showIcon
          message={formatValidity(assignment.validFrom, assignment.validTo, t('common.openEnded'))}
          description={te('assignments.correctHint')}
          style={{ marginBottom: 16 }}
        />
      ) : null}
      <Form<CorrectValues> form={form} layout="vertical">
        <Form.Item name="reason" label={te('assignments.correctionReason')} rules={[{ required: true, whitespace: true, message: te('errors.reason-required') }, { max: 1000 }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
        <AssignmentFields legalEntityId={legalEntityId} employmentId={employmentId} />
      </Form>
    </FormDrawer>
  );
}

function CancelAssignmentDrawer({ assignment, onClose, employmentId }: { assignment: AssignmentView | null; onClose: () => void; employmentId: string }) {
  const { t: te } = useTranslation(NS);
  const { t } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<{ reason: string }>();
  const cancel = useCancelAssignment(employmentId);

  useEffect(() => {
    if (!assignment) return;
    form.resetFields();
    cancel.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [assignment, form]);

  const submit = async () => {
    if (!assignment) return;
    let v: { reason: string };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await cancel.mutateAsync({ assignmentId: assignment.id, reason: v.reason.trim() });
      fb.done(te('assignments.cancelled'));
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open={assignment !== null}
      onClose={onClose}
      title={te('assignments.cancelTitle')}
      onSubmit={() => void submit()}
      submitting={cancel.isPending}
      error={cancel.error}
      submitLabel={t('action.confirm')}
      width={480}
    >
      {assignment ? (
        <Typography.Paragraph>{formatValidity(assignment.validFrom, assignment.validTo, t('common.openEnded'))}</Typography.Paragraph>
      ) : null}
      <Form form={form} layout="vertical">
        <Form.Item name="reason" label={te('assignments.cancelReason')} rules={[{ required: true, whitespace: true, message: te('errors.reason-required') }, { max: 1000 }]}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

