import { useEffect, useState } from 'react';
import { Alert, Button, Card, Checkbox, DatePicker, Form, Input, Result, Select, Skeleton, Space, Tag, Typography, Upload } from 'antd';
import { EditOutlined, PaperClipOutlined, PlusOutlined, SendOutlined, UploadOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, DescriptionList, FormDrawer, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime } from '@/shared/format';
import { applyFieldErrors, fileForm, LegalEntityName, toIsoDate } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import {
  canCancel,
  canEdit,
  canEditPeriods,
  canSubmit,
  SERVICE_PERIOD_KINDS,
  type SeparationResult,
  type SeparationView,
  type ServicePeriodKind,
  type ServicePeriodView,
} from './api';
import {
  AssessmentView,
  AttributeFields,
  fromSeparation,
  groundLabel,
  NS,
  reasonLabel,
  SeparationStatusTag,
  toAttributes,
  useLabel,
  type AttributeValues,
} from './parts';
import {
  useAddServicePeriod,
  useCancelSeparation,
  useRemoveServicePeriod,
  useSeparation,
  useSeparationReasons,
  useSubmitSeparation,
  useUpdateSeparation,
  useUploadSeparationEvidence,
} from './queries';

type Drawer = 'edit' | 'evidence' | 'submit' | 'cancel' | 'period' | null;

/**
 * One separation (US-PPL-060..067): the stored attributes and notice / severance figures, the latest assessment
 * returned by an update or submission, service periods for severance, and the DRAFT → SUBMITTED commands.
 */
export function SeparationDetailPage({ separationId }: { separationId: UUID }) {
  const { t, i18n } = useTranslation(NS);
  const { t: tp } = useTranslation('people');
  const label = useLabel();
  const query = useSeparation(separationId);
  const s = query.data;
  const reasons = useSeparationReasons(s?.legalEntityId, null, !!s);
  const [drawer, setDrawer] = useState<Drawer>(null);
  const [lastResult, setLastResult] = useState<SeparationResult | null>(null);

  if (query.isLoading) return <Skeleton active />;
  if (query.error || !s) {
    return <Result status="404" title={t('detail.notFound')} extra={query.error ? <ProblemAlert error={query.error} /> : null} />;
  }

  const close = () => setDrawer(null);
  const days = (n: number | null | undefined) =>
    n === null || n === undefined
      ? null
      : s.noticeDayBasis
        ? t('daysWithBasis', { count: n, basis: t(`noticeBasis.${s.noticeDayBasis}`) })
        : t('days', { count: n });

  return (
    <div>
      <PageHeader
        title={
          <Space wrap>
            {t('detail.title', { name: s.fullName })}
            <SeparationStatusTag status={s.status} />
          </Space>
        }
        subTitle={
          <Space split="·" wrap>
            <Link to="/people/employees/$employmentId" params={{ employmentId: s.employmentId }} search={{ tab: 'separation' }}>
              {s.employeeNo}
            </Link>
            <LegalEntityName id={s.legalEntityId} />
          </Space>
        }
        breadcrumb={[{ label: t('list.title'), to: '/people/separations' }, { label: s.employeeNo }]}
        extra={
          <PermissionGate anyOf={P.SEPARATION_UPDATE}>
            <Space wrap>
              {canEdit(s.status) ? (
                <Button icon={<EditOutlined />} onClick={() => setDrawer('edit')}>
                  {t('detail.edit')}
                </Button>
              ) : null}
              {canEdit(s.status) ? (
                <Button icon={<PaperClipOutlined />} onClick={() => setDrawer('evidence')}>
                  {t('detail.evidence')}
                </Button>
              ) : null}
              {canSubmit(s.status) ? (
                <Button type="primary" icon={<SendOutlined />} onClick={() => setDrawer('submit')}>
                  {t('detail.submit')}
                </Button>
              ) : null}
              {canCancel(s.status) ? (
                <Button danger onClick={() => setDrawer('cancel')}>
                  {t('detail.cancel')}
                </Button>
              ) : null}
            </Space>
          </PermissionGate>
        }
      />

      {lastResult ? (
        <Card size="small" title={t('detail.lastAssessment')} style={{ marginBottom: 16 }} extra={<Button size="small" type="text" onClick={() => setLastResult(null)}>×</Button>}>
          <AssessmentView
            notice={lastResult.notice}
            protection={lastResult.protection}
            statutoryCode={lastResult.statutoryCode}
            severanceType={lastResult.separation.severanceType}
            severanceEligibility={lastResult.separation.severanceEligibility}
            warnings={lastResult.warnings}
          />
        </Card>
      ) : null}

      <Card title={t('detail.summary')} style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: t('field.legalGround'), value: groundLabel(reasons.data, s.legalGroundCode, i18n.language) },
            { label: t('field.reason'), value: reasonLabel(reasons.data, s.reasonCode, i18n.language), hideEmpty: true },
            { label: t('field.separationType'), value: label('type', s.separationType) },
            { label: t('field.initiatedBy'), value: label('initiatedBy', s.initiatedBy) },
            { label: t('field.phase'), value: s.employmentPhase ? tp(`employmentStatus.${s.employmentPhase}`, { defaultValue: s.employmentPhase }) : null },
            { label: t('field.requestDate'), value: formatDate(s.requestDate) },
            { label: t('field.noticeDate'), value: formatDate(s.noticeDate) },
            { label: t('field.lastWorkingDate'), value: formatDate(s.lastWorkingDate) },
            { label: t('field.terminationDate'), value: formatDate(s.terminationDate) },
            { label: t('field.accessRevocationAt'), value: formatDateTime(s.accessRevocationAt) || formatDate(s.accessRevocationDate) },
            { label: t('field.rehireEligible'), value: s.rehireEligible === null || s.rehireEligible === undefined ? null : s.rehireEligible ? tp('common.yes', { defaultValue: '✓' }) : '✗' },
            { label: t('field.signatoryName'), value: s.signatoryName, hideEmpty: true },
            { label: t('field.signatoryTitle'), value: s.signatoryTitle, hideEmpty: true },
            { label: t('field.employeeNote'), value: s.employeeNote, hideEmpty: true, span: 3 },
            { label: t('field.personalEmail'), value: s.noPersonalEmail ? t('field.noPersonalEmail') : null, hideEmpty: true },
          ]}
        />
      </Card>

      <Card title={t('detail.notice')} style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: t('field.requiredNotice'), value: s.requiredNoticeDays === null || s.requiredNoticeDays === undefined ? t('assessment.noNotice') : days(s.requiredNoticeDays) },
            {
              label: t('field.shortfall'),
              value: s.noticeShortfallDays ? <Typography.Text type="danger">{days(s.noticeShortfallDays)}</Typography.Text> : t('assessment.noShortfall'),
            },
            { label: t('field.specialTradeCode'), value: s.specialTradeCode, hideEmpty: true },
            { label: t('field.noticeExemptReasonCode'), value: s.noticeExemptReasonCode, hideEmpty: true },
            { label: t('field.severanceType'), value: label('severanceType', s.severanceType) },
            { label: t('field.severanceEligibility'), value: label('eligibility', s.severanceEligibility) },
            { label: t('field.settlementDueDate'), value: formatDate(s.settlementDueDate), hideEmpty: true },
            { label: t('detail.payrollResult'), value: s.payrollResultRef ? <code>{s.payrollResultRef}</code> : null, hideEmpty: true },
            {
              label: t('detail.decision'),
              value: s.decisionId ? (
                <Link to="/people/decisions/$decisionId" params={{ decisionId: s.decisionId }}>
                  {t('tab.open')}
                </Link>
              ) : null,
              hideEmpty: true,
            },
          ]}
        />
      </Card>

      <Card title={t('detail.protection')} style={{ marginBottom: 16 }}>
        {s.protectedEmployee ? <Tag color="orange">{t('assessment.protected')}</Tag> : <Tag>{t('assessment.notProtected')}</Tag>}
        {s.protectedOverrideReason ? (
          <Typography.Paragraph style={{ marginTop: 8 }}>
            <b>{t('field.protectedOverrideReason')}:</b> {s.protectedOverrideReason}
          </Typography.Paragraph>
        ) : null}
        {s.protectedOverrideDocumentId ? (
          <Typography.Paragraph>
            <PaperClipOutlined /> {t('detail.evidenceDocument')} <code>{s.protectedOverrideDocumentId}</code>
          </Typography.Paragraph>
        ) : null}
      </Card>

      <ServicePeriodsCard separation={s} onAdd={() => setDrawer('period')} />

      <EditDrawer open={drawer === 'edit'} onClose={close} separation={s} onResult={setLastResult} />
      <EvidenceDrawer open={drawer === 'evidence'} onClose={close} separation={s} />
      <SubmitDrawer open={drawer === 'submit'} onClose={close} separation={s} onResult={setLastResult} />
      <CancelDrawer open={drawer === 'cancel'} onClose={close} separation={s} />
      <AddPeriodDrawer open={drawer === 'period'} onClose={close} separation={s} />
    </div>
  );
}

// -------------------------------------------------------------------------------------------- service periods

function ServicePeriodsCard({ separation: s, onAdd }: { separation: SeparationView; onAdd: () => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const remove = useRemoveServicePeriod(s.id);
  const editable = canEditPeriods(s.status);

  const onRemove = async (periodId: UUID) => {
    try {
      await remove.mutateAsync(periodId);
      fb.done(t('detail.periodRemoved'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <Card
      title={t('detail.periods')}
      extra={
        editable ? (
          <PermissionGate anyOf={P.SEPARATION_UPDATE}>
            <Button icon={<PlusOutlined />} onClick={onAdd}>
              {t('detail.addPeriod')}
            </Button>
          </PermissionGate>
        ) : null
      }
    >
      <DataTable<ServicePeriodView>
        size="small"
        data={s.servicePeriods}
        columns={[
          { title: t('field.periodKind'), dataIndex: 'periodKind', render: (k: ServicePeriodKind) => t(`periodKind.${k}`, { defaultValue: k }) },
          { title: t('field.periodFrom'), dataIndex: 'periodFrom', render: (v: string) => formatDate(v) },
          { title: t('field.periodTo'), dataIndex: 'periodTo', render: (v: string) => formatDate(v) },
          { title: t('field.note'), dataIndex: 'note' },
          { title: t('field.derived'), dataIndex: 'derived', render: (d: boolean) => (d ? <Tag>{t('field.derived')}</Tag> : null) },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_, p) =>
              editable && !p.derived ? (
                <PermissionGate anyOf={P.SEPARATION_UPDATE}>
                  <ConfirmButton danger size="small" type="link" title={t('detail.removePeriod')} onConfirm={() => onRemove(p.id)}>
                    {tc('action.delete')}
                  </ConfirmButton>
                </PermissionGate>
              ) : null,
          },
        ]}
      />
    </Card>
  );
}

interface PeriodValues {
  periodKind: ServicePeriodKind;
  periodFrom: Dayjs;
  periodTo: Dayjs;
  note?: string;
}

function AddPeriodDrawer({ open, onClose, separation: s }: { open: boolean; onClose: () => void; separation: SeparationView }) {
  const { t } = useTranslation(NS);
  const { t: tp } = useTranslation('people');
  const fb = useFeedback();
  const [form] = Form.useForm<PeriodValues>();
  const add = useAddServicePeriod(s.id);

  useEffect(() => {
    if (open) {
      form.resetFields();
      add.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset on open only
  }, [open, form]);

  const submit = async () => {
    let v: PeriodValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await add.mutateAsync({
        periodKind: v.periodKind,
        periodFrom: toIsoDate(v.periodFrom) ?? '',
        periodTo: toIsoDate(v.periodTo) ?? '',
        note: v.note?.trim() || null,
      });
      fb.done(t('detail.periodAdded'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const required = [{ required: true, message: tp('common.required') }];
  return (
    <FormDrawer open={open} onClose={onClose} title={t('detail.addPeriod')} width={440} onSubmit={() => void submit()} submitting={add.isPending} error={add.error}>
      <Form<PeriodValues> form={form} layout="vertical">
        <Form.Item name="periodKind" label={t('field.periodKind')} rules={required}>
          <Select options={SERVICE_PERIOD_KINDS.map((k) => ({ value: k, label: t(`periodKind.${k}`) }))} />
        </Form.Item>
        <Form.Item name="periodFrom" label={t('field.periodFrom')} rules={required}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="periodTo"
          label={t('field.periodTo')}
          dependencies={['periodFrom']}
          rules={[
            ...required,
            ({ getFieldValue }) => ({
              validator: (_, v: Dayjs | undefined) => {
                const from = getFieldValue('periodFrom') as Dayjs | undefined;
                return !v || !from || !v.isBefore(from, 'day') ? Promise.resolve() : Promise.reject(new Error(t('errors.invalid-period')));
              },
            }),
          ]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="note" label={t('field.note')} rules={[{ max: 500 }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// -------------------------------------------------------------------------------------------- commands

function EditDrawer({
  open,
  onClose,
  separation: s,
  onResult,
}: {
  open: boolean;
  onClose: () => void;
  separation: SeparationView;
  onResult: (r: SeparationResult) => void;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm<AttributeValues>();
  const reasons = useSeparationReasons(s.legalEntityId, null, open);
  const update = useUpdateSeparation(s.id);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue(fromSeparation(s));
    update.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset on open only
  }, [open, form]);

  const submit = async () => {
    let v: AttributeValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      onResult(await update.mutateAsync({ version: s.version, attributes: toAttributes(v) }));
      fb.done(t('detail.edited'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={t('detail.edit')} width={640} onSubmit={() => void submit()} submitting={update.isPending} error={update.error}>
      <Form<AttributeValues> form={form} layout="vertical">
        <AttributeFields form={form} catalogue={reasons.data} catalogueLoading={reasons.isLoading} />
      </Form>
    </FormDrawer>
  );
}

/** Mounted only while open, so every opening starts from a clean state. */
function EvidenceDrawer(props: { open: boolean; onClose: () => void; separation: SeparationView }) {
  return props.open ? <EvidenceDrawerBody {...props} /> : null;
}

function EvidenceDrawerBody({ open, onClose, separation: s }: { open: boolean; onClose: () => void; separation: SeparationView }) {
  const { t } = useTranslation(NS);
  const { t: tp } = useTranslation('people');
  const fb = useFeedback();
  const upload = useUploadSeparationEvidence(s.id);
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');

  const submit = async () => {
    if (!file) return;
    try {
      await upload.mutateAsync(fileForm(file, { title: title.trim() || null }));
      fb.done(t('detail.evidenceUploaded'));
      onClose();
    } catch {
      /* shown in the drawer */
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('detail.evidence')}
      width={440}
      onSubmit={() => void submit()}
      submitting={upload.isPending}
      submitLabel={tp('common.upload')}
      error={upload.error}
    >
      <Alert type="info" showIcon message={t('detail.evidenceHint')} style={{ marginBottom: 16 }} />
      <Form layout="vertical">
        <Form.Item label={t('field.evidenceTitle')}>
          <Input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />
        </Form.Item>
        <Form.Item label={t('field.file')} required>
          <Upload
            maxCount={1}
            beforeUpload={(f) => {
              setFile(f);
              return false;
            }}
            onRemove={() => setFile(null)}
            fileList={file ? [{ uid: '1', name: file.name, status: 'done' }] : []}
          >
            <Button icon={<UploadOutlined />}>{tp('common.upload')}</Button>
          </Upload>
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

interface SubmitValues {
  personalEmail?: string;
  noPersonalEmail?: boolean;
  note?: string;
}

function SubmitDrawer({
  open,
  onClose,
  separation: s,
  onResult,
}: {
  open: boolean;
  onClose: () => void;
  separation: SeparationView;
  onResult: (r: SeparationResult) => void;
}) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm<SubmitValues>();
  const submitM = useSubmitSeparation(s.id);
  const none = Form.useWatch('noPersonalEmail', form);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({ noPersonalEmail: s.noPersonalEmail });
    submitM.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset on open only
  }, [open, form]);

  const submit = async () => {
    let v: SubmitValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      onResult(
        await submitM.mutateAsync({
          version: s.version,
          personalEmail: v.noPersonalEmail ? null : v.personalEmail?.trim() || null,
          noPersonalEmail: !!v.noPersonalEmail,
          note: v.note?.trim() || null,
        }),
      );
      fb.done(t('detail.submitted'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('detail.submit')}
      width={480}
      onSubmit={() => void submit()}
      submitting={submitM.isPending}
      submitLabel={t('detail.submit')}
      error={submitM.error}
    >
      <Form<SubmitValues> form={form} layout="vertical">
        <Form.Item
          name="personalEmail"
          label={t('field.personalEmail')}
          extra={t('detail.submitHint')}
          dependencies={['noPersonalEmail']}
          rules={[
            { type: 'email' },
            { max: 254 },
            ({ getFieldValue }) => ({
              validator: (_, v: string | undefined) =>
                getFieldValue('noPersonalEmail') || (v && v.trim()) ? Promise.resolve() : Promise.reject(new Error(t('detail.emailOrNone'))),
            }),
          ]}
        >
          <Input type="email" disabled={!!none} />
        </Form.Item>
        <Form.Item name="noPersonalEmail" valuePropName="checked">
          <Checkbox onChange={() => void form.validateFields(['personalEmail']).catch(() => undefined)}>{t('field.noPersonalEmail')}</Checkbox>
        </Form.Item>
        <Form.Item name="note" label={t('field.submitNote')} rules={[{ max: 2000 }]}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

function CancelDrawer({ open, onClose, separation: s }: { open: boolean; onClose: () => void; separation: SeparationView }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [form] = Form.useForm<{ reason?: string }>();
  const cancel = useCancelSeparation(s.id);

  useEffect(() => {
    if (open) {
      form.resetFields();
      cancel.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset on open only
  }, [open, form]);

  const submit = async () => {
    const v = await form.validateFields().catch(() => null);
    if (!v) return;
    try {
      await cancel.mutateAsync({ version: s.version, reason: v.reason?.trim() || null });
      fb.done(t('detail.cancelled'));
      onClose();
    } catch {
      /* shown in the drawer */
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('detail.cancel')}
      width={440}
      onSubmit={() => void submit()}
      submitting={cancel.isPending}
      submitLabel={t('detail.cancel')}
      error={cancel.error}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="reason" label={t('field.cancelReason')} rules={[{ max: 2000 }]}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
