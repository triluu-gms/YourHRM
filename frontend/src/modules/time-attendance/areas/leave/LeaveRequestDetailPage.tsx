import { useEffect, useState } from 'react';
import { Alert, Button, Card, DatePicker, Form, Input, Modal, Select, Space, Spin } from 'antd';
import type { Dayjs } from 'dayjs';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DescriptionList, FormDrawer, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import type { LeaveRequestRow, SiClaimView } from './api';
import { useEarlyReturn, useLeaveRequest, useLeaveTypes, useSiClaim, useUpdateSiClaim, useWithdrawLeave } from './queries';
import { EmploymentRef, formatQty, fromIso, LeaveTypeName, LegalEntityName, NS, RequestStatusTag, toIso, useEnumLabel } from './components';
import { leaveDateRange } from './LeaveRequestsPage';
import { SiClaimFields, siFormToInput } from './NewLeaveRequestDrawer';

/** One leave request: facts, withdraw (pending), maternity early return (approved) and the SI claim data (P3). */
export function LeaveRequestDetailPage({ requestId }: { requestId: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const q = useLeaveRequest(requestId);
  const types = useLeaveTypes();
  const [withdrawing, setWithdrawing] = useState(false);
  const [returning, setReturning] = useState(false);

  if (q.isLoading) return <Spin />;
  if (q.error || !q.data) return <ProblemAlert error={q.error} />;
  const r = q.data;
  const type = types.data?.find((x) => x.id === r.leaveTypeId);
  const isMaternity = type?.data.category === 'MATERNITY';

  return (
    <div>
      <PageHeader
        title={
          <Space>
            {t('detail.title')}
            <RequestStatusTag status={r.status} />
          </Space>
        }
        breadcrumb={[{ label: t('requests.title'), to: '/attendance/leave-requests' }, { label: leaveDateRange(r) }]}
        extra={
          <>
            {r.status === 'PENDING' ? (
              <PermissionGate anyOf={P.LEAVE_REQUEST_CREATE}>
                <Button danger onClick={() => setWithdrawing(true)}>
                  {t('detail.withdraw')}
                </Button>
              </PermissionGate>
            ) : null}
            {r.status === 'APPROVED' && isMaternity && !r.actualReturnDate ? (
              <PermissionGate anyOf={P.LEAVE_REQUEST_MANAGE}>
                <Button onClick={() => setReturning(true)}>{t('detail.earlyReturn')}</Button>
              </PermissionGate>
            ) : null}
          </>
        }
      />
      <Card style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            {
              label: t('field.employee'),
              value: (
                <Link to="/attendance/leave-balances" search={{ employmentId: r.employmentId }}>
                  <EmploymentRef id={r.employmentId} />
                </Link>
              ),
            },
            { label: tc('field.legalEntity'), value: <LegalEntityName id={r.legalEntityId} /> },
            { label: t('field.leaveType'), value: <LeaveTypeName id={r.leaveTypeId} code={r.leaveTypeCode} /> },
            { label: t('field.dates'), value: leaveDateRange(r) },
            {
              label: t('field.duration'),
              value:
                r.requestUnit === 'HOUR'
                  ? t('requests.hoursValue', { value: formatQty(r.durationHours) })
                  : t('requests.daysValue', { value: formatQty(r.durationDays) }),
            },
            { label: t('field.parts'), value: r.requestUnit === 'HOUR' ? '' : <Parts r={r} />, hideEmpty: true },
            { label: tc('field.reason'), value: r.reason },
            { label: t('field.actualReturnDate'), value: formatDate(r.actualReturnDate), hideEmpty: true },
            { label: t('field.attachments'), value: r.attachmentDocumentIds?.length ? r.attachmentDocumentIds.join(', ') : '' },
            { label: tc('field.createdAt'), value: formatDateTime(r.createdAt) },
            { label: t('field.decidedAt'), value: formatDateTime(r.decidedAt), hideEmpty: true },
            { label: t('field.cancelledAt'), value: formatDateTime(r.cancelledAt), hideEmpty: true },
            { label: t('field.cancelReason'), value: r.cancelReason, hideEmpty: true },
            { label: t('field.workflow'), value: r.workflowInstanceId, hideEmpty: true },
          ]}
        />
      </Card>
      <SiClaimCard r={r} category={type?.data.category} required={!!type?.data.requiresSiClaimData} />
      <WithdrawModal r={r} open={withdrawing} onClose={() => setWithdrawing(false)} />
      <EarlyReturnModal r={r} open={returning} onClose={() => setReturning(false)} />
    </div>
  );
}

function Parts({ r }: { r: LeaveRequestRow }) {
  const label = useEnumLabel();
  return (
    <>
      {label('part', r.startPart)} → {label('part', r.endPart)}
    </>
  );
}

function SiClaimCard({ r, category, required }: { r: LeaveRequestRow; category?: string; required: boolean }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const label = useEnumLabel();
  const canView = usePermission(P.LEAVE_SI_DATA_VIEW);
  const [reveal, setReveal] = useState(false);
  const [editing, setEditing] = useState(false);
  const si = useSiClaim(r.id, canView && reveal);
  if (!required && !r.hasSiClaimData) return null;
  const v = si.data;
  const yesNo = (b: boolean | null | undefined) => (b === null || b === undefined ? '' : b ? tc('common.yes') : tc('common.no'));

  return (
    <Card
      title={t('si.title')}
      extra={
        <Space>
          <PermissionGate anyOf={P.LEAVE_SI_DATA_VIEW}>
            {r.hasSiClaimData ? (
              <Button size="small" onClick={() => setReveal((x) => !x)} loading={si.isFetching}>
                {reveal ? t('si.hide') : t('si.show')}
              </Button>
            ) : null}
          </PermissionGate>
          <PermissionGate anyOf={P.LEAVE_REQUEST_MANAGE}>
            <Button size="small" onClick={() => setEditing(true)}>
              {tc('action.edit')}
            </Button>
          </PermissionGate>
        </Space>
      }
    >
      {!r.hasSiClaimData ? <Alert type="info" showIcon message={t('si.none')} style={{ marginBottom: 12 }} /> : null}
      {si.error ? <ProblemAlert error={si.error} style={{ marginBottom: 12 }} /> : null}
      {reveal && v ? (
        <DescriptionList
          items={[
            { label: t('si.certificateType'), value: v.certificateType },
            { label: t('si.certificateNo'), value: v.certificateNo },
            { label: t('si.certificateDate'), value: formatDate(v.certificateDate) },
            { label: t('si.issuingFacility'), value: v.issuingFacility },
            { label: t('si.diagnosisCategory'), value: v.diagnosisCategory },
            { label: t('si.treatmentType'), value: label('treatment', v.treatmentType) },
            { label: t('si.longTermIllness'), value: yesNo(v.longTermIllness) },
            { label: t('si.childBirthDate'), value: formatDate(v.childBirthDate), hideEmpty: true },
            { label: t('si.childId'), value: v.childId, hideEmpty: true },
            { label: t('si.confinementDate'), value: formatDate(v.confinementDate), hideEmpty: true },
            { label: t('si.childrenBornCount'), value: v.childrenBornCount, hideEmpty: true },
            { label: t('si.caesarean'), value: yesNo(v.caesarean), hideEmpty: true },
          ]}
        />
      ) : r.hasSiClaimData ? (
        <DescriptionList
          items={[
            { label: t('si.certificateType'), value: r.siCertificateType },
            { label: t('si.certificateNo'), value: r.siCertificateNoLast4 ? `••••${r.siCertificateNoLast4}` : '' },
            { label: t('si.certificateDate'), value: formatDate(r.siCertificateDate) },
            { label: t('si.childBirthDate'), value: formatDate(r.childBirthDate), hideEmpty: true },
            { label: t('si.childId'), value: r.childIdLast4 ? `••••${r.childIdLast4}` : '', hideEmpty: true },
            { label: t('si.confinementDate'), value: formatDate(r.confinementDate), hideEmpty: true },
            { label: t('si.childrenBornCount'), value: r.childrenBornCount, hideEmpty: true },
          ]}
        />
      ) : null}
      <SiClaimDrawer r={r} category={category} current={canView ? v : undefined} open={editing} onClose={() => setEditing(false)} />
    </Card>
  );
}

function SiClaimDrawer({
  r,
  category,
  current,
  open,
  onClose,
}: {
  r: LeaveRequestRow;
  category?: string;
  current?: SiClaimView;
  open: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm();
  const save = useUpdateSiClaim(r.id);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    const c = current;
    form.setFieldsValue({
      si: c
        ? {
            ...c,
            certificateDate: fromIso(c.certificateDate),
            childBirthDate: fromIso(c.childBirthDate),
            confinementDate: fromIso(c.confinementDate),
          }
        : {
            certificateType: r.siCertificateType ?? undefined,
            certificateDate: fromIso(r.siCertificateDate),
            childBirthDate: fromIso(r.childBirthDate),
            confinementDate: fromIso(r.confinementDate),
            childrenBornCount: r.childrenBornCount ?? undefined,
          },
    });
  }, [open, current, r, form]);

  const submit = async () => {
    let v: { si: Parameters<typeof siFormToInput>[0] };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await save.mutateAsync(siFormToInput(v.si) ?? {});
      fb.saved();
      onClose();
    } catch {
      /* shown in the drawer */
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={t('si.edit')} onSubmit={() => void submit()} submitting={save.isPending} error={save.error} width={720}>
      {!current && r.hasSiClaimData ? <Alert type="warning" showIcon message={t('si.replaceWarning')} style={{ marginBottom: 12 }} /> : null}
      <Form form={form} layout="vertical">
        <SiClaimFields prefix={['si']} category={category} />
      </Form>
    </FormDrawer>
  );
}

function WithdrawModal({ r, open, onClose }: { r: LeaveRequestRow; open: boolean; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const withdraw = useWithdrawLeave(r.id);
  const [reason, setReason] = useState('');
  return (
    <Modal
      open={open}
      title={t('detail.withdrawTitle')}
      okText={tc('action.confirm')}
      cancelText={tc('action.cancel')}
      okButtonProps={{ danger: true, loading: withdraw.isPending }}
      onCancel={onClose}
      destroyOnHidden
      onOk={async () => {
        try {
          await withdraw.mutateAsync(reason || null);
          fb.done(t('detail.withdrawn'));
          onClose();
        } catch {
          /* shown below */
        }
      }}
    >
      {withdraw.error ? <ProblemAlert error={withdraw.error} style={{ marginBottom: 12 }} /> : null}
      <Form layout="vertical">
        <Form.Item label={tc('field.reason')}>
          <Input.TextArea aria-label={tc('field.reason')} rows={3} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function EarlyReturnModal({ r, open, onClose }: { r: LeaveRequestRow; open: boolean; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const ret = useEarlyReturn(r.id);
  const [date, setDate] = useState<Dayjs | null>(null);
  const [docs, setDocs] = useState<string[]>([]);
  return (
    <Modal
      open={open}
      title={t('detail.earlyReturnTitle')}
      okText={tc('action.confirm')}
      cancelText={tc('action.cancel')}
      okButtonProps={{ disabled: !date, loading: ret.isPending }}
      onCancel={onClose}
      destroyOnHidden
      onOk={async () => {
        if (!date) return;
        try {
          await ret.mutateAsync({ returnDate: toIso(date) as string, attachmentDocumentIds: docs.length ? docs : null });
          fb.saved();
          onClose();
        } catch {
          /* shown below */
        }
      }}
    >
      <Alert type="info" showIcon message={t('detail.earlyReturnHint')} style={{ marginBottom: 12 }} />
      {ret.error ? <ProblemAlert error={ret.error} style={{ marginBottom: 12 }} /> : null}
      <Form layout="vertical">
        <Form.Item label={t('field.returnDate')} required>
          <DatePicker
            aria-label={t('field.returnDate')}
            format={DATE_FORMAT}
            style={{ width: '100%' }}
            value={date}
            onChange={setDate}
            disabledDate={(d) => d.isBefore(r.startDate, 'day') || d.isSame(r.startDate, 'day') || d.isAfter(r.endDate, 'day')}
          />
        </Form.Item>
        <Form.Item label={t('field.attachments')} extra={t('requests.attachmentsHint')}>
          <Select mode="tags" tokenSeparators={[',', ' ']} open={false} value={docs} onChange={setDocs} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
