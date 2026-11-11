import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Card, DatePicker, Form, Input, Result, Select, Skeleton } from 'antd';
import { useTranslation } from 'react-i18next';
import dayjs, { type Dayjs } from 'dayjs';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DescriptionList, FormDrawer, PageHeader, ProblemAlert, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime } from '@/shared/format';
import { applyFieldErrors, toIsoDate } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { CLOSED_SEPARATION_STATUSES, type SeparationResult, type SeparationView } from './api';
import { useMyEmployments, useMyResignation, useResign, useWithdrawResignation } from './queries';
import './i18n';

const STATUSES = ['DRAFT', 'SUBMITTED', 'APPROVED', 'SCHEDULED', 'COMPLETED', 'WITHDRAWN', 'REJECTED', 'CANCELLED'];

/**
 * ESS resignation (US-PPL-065, BR-PPL-072): submit a proposed last working day; the line manager then HR approve.
 * Before approval the employee withdraws freely; afterwards the withdrawal needs HR consent.
 */
export function MyResignationPage() {
  const { t: te } = useTranslation('people-ess');
  const query = useMyResignation();
  const [lastResult, setLastResult] = useState<SeparationResult | null>(null);

  if (query.isLoading) return <Skeleton active />;
  if (query.error) return <Result status="warning" title={te('resignation.title')} extra={<ProblemAlert error={query.error} />} />;

  const current = query.data ?? null;
  const open = current && !CLOSED_SEPARATION_STATUSES.includes(current.status) ? current : null;

  return (
    <div>
      <PageHeader title={te('resignation.title')} subTitle={te('resignation.intro')} />
      {lastResult ? <ResultNotes result={lastResult} /> : null}
      {open ? (
        <CurrentResignation separation={open} />
      ) : (
        <>
          <ResignationForm onSubmitted={setLastResult} />
          {current ? <CurrentResignation separation={current} history /> : null}
        </>
      )}
    </div>
  );
}

function CurrentResignation({ separation: s, history = false }: { separation: SeparationView; history?: boolean }) {
  const { t } = useTranslation();
  const { t: te } = useTranslation('people-ess');
  const [withdrawing, setWithdrawing] = useState(false);
  const labels = Object.fromEntries(STATUSES.map((x) => [x, te(`resignation.status.${x}`)]));
  const pending = s.status === 'DRAFT' || s.status === 'SUBMITTED';
  const approved = s.status === 'APPROVED' || s.status === 'SCHEDULED';

  return (
    <Card
      title={history ? te('resignation.history') : te('resignation.current')}
      style={{ marginTop: history ? 16 : 0 }}
      extra={
        !history && (pending || approved) ? (
          <PermissionGate anyOf={P.ESS_REQUEST}>
            <Button danger onClick={() => setWithdrawing(true)}>
              {te('resignation.withdraw')}
            </Button>
          </PermissionGate>
        ) : null
      }
    >
      {!history && pending ? <Alert type="warning" showIcon message={te('pending')} style={{ marginBottom: 12 }} /> : null}
      {!history && approved ? (
        <Alert type="info" showIcon message={te('resignation.withdrawApprovedHint')} style={{ marginBottom: 12 }} />
      ) : null}
      <DescriptionList
        items={[
          { label: te('resignation.employment'), value: s.employeeNo },
          { label: te('resignation.requestDate'), value: formatDate(s.requestDate) },
          { label: te('resignation.lastWorkingDate'), value: formatDate(s.lastWorkingDate) },
          { label: te('resignation.terminationDate'), value: formatDate(s.terminationDate), hideEmpty: true },
          { label: te('resignation.accessRevocationDate'), value: formatDate(s.accessRevocationDate), hideEmpty: true },
          {
            label: te('resignation.notice'),
            hideEmpty: true,
            value:
              s.requiredNoticeDays != null
                ? `${te('resignation.noticeDays', { required: s.requiredNoticeDays })}${
                    s.noticeShortfallDays ? ` · ${te('resignation.shortfall', { days: s.noticeShortfallDays })}` : ''
                  }`
                : null,
          },
          { label: te('resignation.note'), value: s.employeeNote, hideEmpty: true },
          { label: t('field.status'), value: <StatusTag status={s.status} labels={labels} /> },
          { label: te('resignation.outcome.WITHDRAWN'), value: formatDateTime(s.withdrawnAt), hideEmpty: true },
        ]}
      />
      <WithdrawDrawer separation={withdrawing ? s : null} onClose={() => setWithdrawing(false)} />
    </Card>
  );
}

function ResultNotes({ result }: { result: SeparationResult }) {
  const { t: te } = useTranslation('people-ess');
  const shortfall = result.notice?.shortfallDays ?? 0;
  if (shortfall <= 0 && result.warnings.length === 0) return null;
  return (
    <Alert
      type="warning"
      showIcon
      style={{ marginBottom: 16 }}
      message={te('resignation.warnings')}
      description={
        <ul style={{ margin: 0, paddingLeft: 16 }}>
          {shortfall > 0 ? <li>{te('resignation.shortfall', { days: shortfall })}</li> : null}
          {result.warnings.map((w) => (
            <li key={w}>{w}</li>
          ))}
        </ul>
      }
    />
  );
}

interface FormValues {
  employmentId?: UUID;
  proposedLastWorkingDate: Dayjs;
  note?: string;
  personalEmail?: string;
}

function ResignationForm({ onSubmitted }: { onSubmitted: (r: SeparationResult) => void }) {
  const { t: te } = useTranslation('people-ess');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const employments = useMyEmployments();
  const resign = useResign();
  const active = useMemo(
    () => (employments.data ?? []).filter((e) => e.employment.status === 'ACTIVE' || e.employment.status === 'SUSPENDED'),
    [employments.data],
  );

  useEffect(() => {
    if (active.length === 1) form.setFieldValue('employmentId', active[0]!.employment.employmentId);
  }, [active, form]);

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const result = await resign.mutateAsync({
        employmentId: v.employmentId ?? null,
        proposedLastWorkingDate: toIsoDate(v.proposedLastWorkingDate) as string,
        note: v.note?.trim() || null,
        personalEmail: v.personalEmail?.trim() || null,
      });
      fb.done(te('resignation.submitted'));
      onSubmitted(result);
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <Card>
      <Alert type="info" showIcon message={te('resignation.none')} style={{ marginBottom: 16 }} />
      {resign.error ? <ProblemAlert error={resign.error} style={{ marginBottom: 16 }} /> : null}
      <Form<FormValues> form={form} layout="vertical" style={{ maxWidth: 520 }}>
        {active.length > 1 ? (
          <Form.Item
            name="employmentId"
            label={te('resignation.employment')}
            rules={[{ required: true, message: te('resignation.employmentRequired') }]}
          >
            <Select
              options={active.map((e) => ({ value: e.employment.employmentId, label: e.employment.employeeNo }))}
            />
          </Form.Item>
        ) : null}
        <Form.Item
          name="proposedLastWorkingDate"
          label={te('resignation.lastWorkingDate')}
          rules={[{ required: true, message: te('resignation.lastWorkingDateRequired') }]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} disabledDate={(d) => d.isBefore(dayjs(), 'day')} />
        </Form.Item>
        <Form.Item name="note" label={te('resignation.note')} rules={[{ max: 2000 }]}>
          <Input.TextArea rows={3} />
        </Form.Item>
        <Form.Item
          name="personalEmail"
          label={te('resignation.personalEmail')}
          extra={te('resignation.personalEmailHint')}
          rules={[{ type: 'email', message: te('resignation.emailInvalid') }, { max: 254 }]}
        >
          <Input type="email" />
        </Form.Item>
        <PermissionGate anyOf={P.ESS_REQUEST}>
          <Button type="primary" danger loading={resign.isPending} onClick={() => void submit()}>
            {te('resignation.submit')}
          </Button>
        </PermissionGate>
      </Form>
    </Card>
  );
}

function WithdrawDrawer({ separation, onClose }: { separation: SeparationView | null; onClose: () => void }) {
  const { t: te } = useTranslation('people-ess');
  const fb = useFeedback();
  const [form] = Form.useForm<{ note?: string }>();
  const withdraw = useWithdrawResignation();

  useEffect(() => {
    if (separation) {
      form.resetFields();
      withdraw.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer opens
  }, [separation, form]);

  const submit = async () => {
    if (!separation) return;
    const { note } = form.getFieldsValue();
    try {
      const result = await withdraw.mutateAsync({ id: separation.id, note: note?.trim() || null });
      fb.done(te(`resignation.outcome.${result.outcome}`, { defaultValue: result.outcome }));
      onClose();
    } catch {
      /* shown in the drawer */
    }
  };

  return (
    <FormDrawer
      open={separation !== null}
      onClose={onClose}
      title={te('resignation.confirmWithdraw')}
      onSubmit={() => void submit()}
      submitting={withdraw.isPending}
      error={withdraw.error}
      submitLabel={te('resignation.withdraw')}
      width={440}
    >
      <Form form={form} layout="vertical">
        <Form.Item name="note" label={te('resignation.withdrawNote')} rules={[{ max: 2000 }]}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
