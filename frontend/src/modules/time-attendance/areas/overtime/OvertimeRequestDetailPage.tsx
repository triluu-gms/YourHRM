import { useState } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Result, Skeleton, Space, Tag, Typography } from 'antd';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, DescriptionList, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { formatDate, formatDateTime } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import type { CategoryMinutes, Finding } from './api';
import { useOvertimeActions, useOvertimeRequest } from './queries';
import { CategoryLabel, FindingsList, findingsOf, formatTime, LegalEntityName, NS, OvertimeStatusTag, useHours } from './components';

type Dialog = 'submit' | 'cancel' | null;

/**
 * One overtime request: details, planned categories, and the actions of its state — record consent (DRAFT, before
 * submit), submit with the legal checks (422 lists every finding), cancel (DRAFT, or APPROVED with a reason).
 */
export function OvertimeRequestDetailPage({ requestId }: { requestId: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const hours = useHours();
  const q = useOvertimeRequest(requestId);
  const actions = useOvertimeActions(requestId);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [findings, setFindings] = useState<Finding[] | null>(null);
  const [submitForm] = Form.useForm<{ capOverrideReason?: string }>();
  const [cancelForm] = Form.useForm<{ reason?: string }>();

  const r = q.data;
  if (q.isLoading) return <Skeleton active />;
  if (q.error || !r) {
    return <Result status="404" title={t('detail.notFound')} extra={q.error ? <ProblemAlert error={q.error} /> : null} />;
  }

  const draft = r.status === 'DRAFT';
  const cancellable = draft || r.status === 'APPROVED';

  const onConsent = async () => {
    try {
      await actions.consent.mutateAsync();
      fb.done(t('detail.consented'));
    } catch (e) {
      fb.failed(e);
    }
  };

  const onSubmit = async () => {
    const v = await submitForm.validateFields().catch(() => null);
    if (!v) return;
    try {
      const out = await actions.submit.mutateAsync(v.capOverrideReason || null);
      setFindings(out.findings);
      if (out.submitted) {
        fb.done(t('detail.submitted'));
        setDialog(null);
      }
    } catch (e) {
      setFindings(findingsOf(e));
    }
  };

  const onCancel = async () => {
    const v = await cancelForm.validateFields().catch(() => null);
    if (!v) return;
    try {
      await actions.cancel.mutateAsync(v.reason || null);
      fb.done(t('detail.cancelled'));
      setDialog(null);
    } catch {
      // shown in the dialog
    }
  };

  const openSubmit = () => {
    actions.submit.reset();
    setFindings(null);
    setDialog('submit');
  };
  const openCancel = () => {
    actions.cancel.reset();
    setDialog('cancel');
  };

  return (
    <div>
      <PageHeader
        title={
          <Space wrap>
            {t('detail.title', { date: formatDate(r.workDate) })}
            <OvertimeStatusTag status={r.status} />
          </Space>
        }
        subTitle={`${formatTime(r.plannedStartAt)} – ${formatTime(r.plannedEndAt)} · ${hours(r.plannedMinutes)}`}
        breadcrumb={[{ label: t('detail.back'), to: '/attendance/overtime-requests' }, { label: formatDate(r.workDate) }]}
        extra={
          <>
            <Link to="/attendance/overtime-requests" search={{ employmentId: r.employmentId }}>
              <Button>{t('detail.back')}</Button>
            </Link>
            <PermissionGate anyOf={P.OVERTIME_CONSENT}>
              {draft && !r.employeeConsent ? (
                <ConfirmButton title={t('detail.confirmConsent')} loading={actions.consent.isPending} onConfirm={onConsent}>
                  {t('detail.consent')}
                </ConfirmButton>
              ) : null}
            </PermissionGate>
            <PermissionGate anyOf={P.OVERTIME_REQUEST}>
              {draft ? (
                <Button type="primary" onClick={openSubmit}>
                  {t('detail.submit')}
                </Button>
              ) : null}
              {cancellable ? (
                <Button danger onClick={openCancel}>
                  {t('detail.cancel')}
                </Button>
              ) : null}
            </PermissionGate>
          </>
        }
      />

      {findings && !dialog && findings.length > 0 ? (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={t('detail.findings')} description={<FindingsList findings={findings} />} />
      ) : null}

      <Card size="small" style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: t('field.workDate'), value: formatDate(r.workDate) },
            { label: t('field.time'), value: `${formatTime(r.plannedStartAt)} – ${formatTime(r.plannedEndAt)}` },
            { label: t('field.plannedHours'), value: hours(r.plannedMinutes) },
            { label: t('field.status'), value: <OvertimeStatusTag status={r.status} /> },
            { label: tc('field.legalEntity'), value: <LegalEntityName id={r.legalEntityId} /> },
            { label: t('field.reason'), value: r.reason },
            {
              label: t('field.consent'),
              value: r.employeeConsent ? <Tag color="green">{tc('common.yes')}</Tag> : <Tag color="red">{tc('common.no')}</Tag>,
            },
            { label: t('field.consentMethod'), value: r.consentMethod ? t(`consentMethod.${r.consentMethod}`) : null, hideEmpty: true },
            { label: t('field.consentAt'), value: formatDateTime(r.consentAt), hideEmpty: true },
            { label: t('field.protectedWorker'), value: r.protectedWorker ? <Tag color="purple">{tc('common.yes')}</Tag> : tc('common.no') },
            { label: t('field.capOverride'), value: r.capOverride ? <Tag color="orange">{tc('common.yes')}</Tag> : tc('common.no') },
            { label: t('field.capOverrideReason'), value: r.capOverrideReason, hideEmpty: true },
            { label: t('field.requestGroup'), value: r.requestGroupId ? <Typography.Text code>{r.requestGroupId}</Typography.Text> : null, hideEmpty: true },
            { label: t('field.workflow'), value: r.workflowInstanceId ? <Typography.Text code>{r.workflowInstanceId}</Typography.Text> : null, hideEmpty: true },
            { label: t('field.decidedAt'), value: formatDateTime(r.decidedAt), hideEmpty: true },
            { label: t('field.version'), value: r.version },
          ]}
        />
      </Card>

      <Card size="small" title={t('detail.categories')}>
        {r.categories.length === 0 ? (
          <Typography.Text type="secondary">{t('detail.noCategories')}</Typography.Text>
        ) : (
          <DataTable<CategoryMinutes>
            rowKey="category"
            data={r.categories}
            columns={[
              { title: t('field.category'), dataIndex: 'category', render: (c: string) => <CategoryLabel code={c} /> },
              { title: t('field.minutes'), dataIndex: 'minutes', render: (m: number) => hours(m) },
              { title: t('field.precededByDayOt'), dataIndex: 'precededByDayOt', render: (v: boolean) => (v ? tc('common.yes') : '') },
            ]}
          />
        )}
      </Card>

      <Modal
        open={dialog === 'submit'}
        title={t('detail.submitTitle')}
        okText={t('detail.submit')}
        cancelText={tc('action.cancel')}
        confirmLoading={actions.submit.isPending}
        onOk={() => void onSubmit()}
        onCancel={() => setDialog(null)}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">{t('detail.submitHint')}</Typography.Paragraph>
        {actions.submit.error ? <ProblemAlert error={actions.submit.error} style={{ marginBottom: 12 }} /> : null}
        {findings && findings.length > 0 ? (
          <Alert
            type={findings.some((f) => f.severity === 'BLOCK') ? 'error' : 'warning'}
            showIcon
            style={{ marginBottom: 12 }}
            message={findings.some((f) => f.severity === 'BLOCK') ? t('detail.notSubmitted') : t('detail.findings')}
            description={<FindingsList findings={findings} />}
          />
        ) : null}
        <Form form={submitForm} layout="vertical" preserve={false}>
          <PermissionGate anyOf={P.OVERTIME_CAP_OVERRIDE}>
            <Form.Item name="capOverrideReason" label={t('field.capOverrideReason')} extra={t('field.capOverrideHint')}>
              <Input.TextArea rows={2} maxLength={500} />
            </Form.Item>
          </PermissionGate>
        </Form>
      </Modal>

      <Modal
        open={dialog === 'cancel'}
        title={t('detail.cancelTitle')}
        okText={t('detail.cancel')}
        okButtonProps={{ danger: true }}
        cancelText={tc('action.cancel')}
        confirmLoading={actions.cancel.isPending}
        onOk={() => void onCancel()}
        onCancel={() => setDialog(null)}
        destroyOnHidden
      >
        {r.status === 'APPROVED' ? <Alert type="info" showIcon style={{ marginBottom: 12 }} message={t('detail.cancelApprovedHint')} /> : null}
        {actions.cancel.error ? <ProblemAlert error={actions.cancel.error} style={{ marginBottom: 12 }} /> : null}
        <Form form={cancelForm} layout="vertical" preserve={false}>
          <Form.Item
            name="reason"
            label={t('field.cancelReason')}
            rules={r.status === 'APPROVED' ? [{ required: true, whitespace: true, message: tc('common.required') }] : []}
          >
            <Input.TextArea rows={3} maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
