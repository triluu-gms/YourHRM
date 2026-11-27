import { useState } from 'react';
import { Alert, Button, Card, Empty, Form, Result, Skeleton, Space, Tabs } from 'antd';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, DescriptionList, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { formatDate, formatDateTime, formatNumber } from '@/shared/format';
import { ATT_NS, EmployeeSelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import type { AttendanceResult, Hold, PeriodView, RetroCandidate } from './api';
import { useChecklist, usePeriod, usePeriodActions, usePeriodHolds, usePeriodResults, useRetroRequests } from './queries';
import { AreaStatusTag, ChecklistSummary, ChecklistTable, CloseDialog, LegalEntityName, NS, range, ReasonDialog, ShortId } from './components';

type Dialog = 'close' | 'reopen' | 'earlyClose' | null;

function ChecklistTab({ period }: { period: PeriodView }) {
  const { t } = useTranslation(NS);
  const [employmentId, setEmploymentId] = useState<UUID | undefined>();
  const q = useChecklist(period.id, employmentId);
  return (
    <Card
      size="small"
      extra={
        <Form layout="inline">
          <Form.Item label={t('detail.employeeChecklist')}>
            <EmployeeSelect
              style={{ minWidth: 280 }}
              legalEntityId={period.legalEntityId}
              aria-label={t('detail.employeeChecklist')}
              value={employmentId}
              onChange={(v) => setEmploymentId(v ?? undefined)}
            />
          </Form.Item>
        </Form>
      }
    >
      {q.error ? <ProblemAlert error={q.error} style={{ marginBottom: 12 }} /> : null}
      {q.data ? <ChecklistSummary checklist={q.data} /> : null}
      <ChecklistTable items={q.data?.items} loading={q.isLoading} />
    </Card>
  );
}

function CloseTab({ period, openDialog }: { period: PeriodView; openDialog: (d: Dialog) => void }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const actions = usePeriodActions(period.id);
  const editable = period.status === 'OPEN' || period.status === 'IN_REVIEW';
  const stateText: Record<string, string> = {
    OPEN: t('actions.stateOpen'),
    IN_REVIEW: t('actions.stateInReview'),
    CLOSED: t('actions.stateClosed'),
    LOCKED: t('actions.stateLocked'),
  };

  const onReview = async () => {
    try {
      await actions.review.mutateAsync();
      fb.done(t('actions.reviewed'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <Card size="small">
      <Space direction="vertical" style={{ width: '100%' }}>
        <Alert type="info" showIcon message={stateText[period.status] ?? period.status} />
        {period.activeHold ? (
          <Alert type="warning" showIcon message={t('detail.heldAlert', { version: period.activeHold.resultVersion })} />
        ) : null}
        {period.reopenRequestedAt ? (
          <Alert
            type="warning"
            showIcon
            message={t('detail.reopenRequested', { at: formatDateTime(period.reopenRequestedAt), reason: period.reopenRequestReason ?? '' })}
          />
        ) : null}
        <Space wrap>
          <PermissionGate anyOf={P.PERIOD_CLOSE}>
            {period.status === 'OPEN' ? (
              <ConfirmButton title={t('actions.confirmReview')} loading={actions.review.isPending} onConfirm={onReview}>
                {t('actions.review')}
              </ConfirmButton>
            ) : null}
            {editable ? (
              <Button type="primary" onClick={() => openDialog('close')}>
                {period.currentVersion > 0 ? t('actions.reclose') : t('actions.close')}
              </Button>
            ) : null}
          </PermissionGate>
          <PermissionGate anyOf={P.PERIOD_REOPEN}>
            {period.status === 'CLOSED' ? (
              <Button danger onClick={() => openDialog('reopen')}>
                {t('actions.reopen')}
              </Button>
            ) : null}
          </PermissionGate>
        </Space>
      </Space>
    </Card>
  );
}

function EarlyCloseTab({
  period,
  employmentId,
  setEmploymentId,
  openDialog,
}: {
  period: PeriodView;
  employmentId?: UUID;
  setEmploymentId: (id: UUID | undefined) => void;
  openDialog: (d: Dialog) => void;
}) {
  const { t } = useTranslation(NS);
  const { t: ta } = useTranslation(ATT_NS);
  const q = useChecklist(period.id, employmentId, !!employmentId);
  const editable = period.status === 'OPEN' || period.status === 'IN_REVIEW';
  return (
    <Card size="small">
      <Space direction="vertical" style={{ width: '100%' }}>
        <Alert type="info" showIcon message={t('actions.earlyCloseHint')} />
        <Space wrap>
          <EmployeeSelect
            style={{ minWidth: 320 }}
            legalEntityId={period.legalEntityId}
            aria-label={ta('common.employee')}
            value={employmentId}
            onChange={(v) => setEmploymentId(v ?? undefined)}
          />
          <PermissionGate anyOf={P.PERIOD_CLOSE}>
            {editable && employmentId ? (
              <Button type="primary" onClick={() => openDialog('earlyClose')}>
                {t('actions.earlyClose')}
              </Button>
            ) : null}
          </PermissionGate>
        </Space>
        {!employmentId ? (
          <Empty description={t('actions.selectEmployee')} />
        ) : (
          <>
            {q.error ? <ProblemAlert error={q.error} /> : null}
            {q.data ? <ChecklistSummary checklist={q.data} /> : null}
            <ChecklistTable items={q.data?.items} loading={q.isLoading} />
          </>
        )}
      </Space>
    </Card>
  );
}

function ResultsTab({ periodId }: { periodId: UUID }) {
  const { t } = useTranslation(NS);
  const q = usePeriodResults(periodId);
  return (
    <DataTable<AttendanceResult>
      data={q.data}
      isLoading={q.isLoading}
      error={q.error}
      columns={[
        {
          title: t('field.version'),
          dataIndex: 'version',
          render: (v: number, r: AttendanceResult) => (
            <Link to="/attendance/attendance-results/$resultId" params={{ resultId: r.id }}>
              v{v}
            </Link>
          ),
        },
        { title: t('field.scope'), dataIndex: 'scope', render: (s: string) => t(`scope.${s}`, { defaultValue: s }) },
        { title: t('field.kind'), dataIndex: 'kind', render: (s: string) => t(`kind.${s}`, { defaultValue: s }) },
        { title: t('field.status'), dataIndex: 'status', render: (s: string) => <AreaStatusTag status={s} /> },
        { title: t('field.closeCause'), dataIndex: 'closeCause', render: (s: string) => t(`cause.${s}`, { defaultValue: s }) },
        { title: t('field.employee'), dataIndex: 'employmentId', render: (id: UUID | null) => <ShortId id={id} /> },
        { title: t('field.attRange'), key: 'att', render: (_: unknown, r: AttendanceResult) => range(r.attDateFrom, r.attDateTo) },
        { title: t('field.lineCount'), dataIndex: 'lineCount', render: (n: number | null) => formatNumber(n) },
        { title: t('field.publishedAt'), dataIndex: 'publishedAt', render: (d: string) => formatDateTime(d) },
      ]}
    />
  );
}

function HoldsTab({ periodId }: { periodId: UUID }) {
  const { t } = useTranslation(NS);
  const q = usePeriodHolds(periodId);
  return (
    <DataTable<Hold>
      data={q.data}
      isLoading={q.isLoading}
      error={q.error}
      columns={[
        { title: t('field.status'), dataIndex: 'status', render: (s: string) => <AreaStatusTag status={s} /> },
        { title: t('field.scope'), dataIndex: 'scope', render: (s: string) => t(`scope.${s}`, { defaultValue: s }) },
        { title: t('field.employee'), dataIndex: 'employmentId', render: (id: UUID | null) => <ShortId id={id} /> },
        {
          title: t('field.resultVersion'),
          dataIndex: 'resultVersion',
          render: (v: number, h: Hold) => (
            <Link to="/attendance/attendance-results/$resultId" params={{ resultId: h.resultId }}>
              v{v}
            </Link>
          ),
        },
        { title: t('field.payrollRun'), dataIndex: 'payrollRunId', render: (id: UUID) => <ShortId id={id} /> },
        { title: t('field.heldAt'), dataIndex: 'heldAt', render: (d: string) => formatDateTime(d) },
        { title: t('field.releasedAt'), dataIndex: 'releasedAt', render: (d: string) => formatDateTime(d) },
        { title: t('field.releaseReason'), dataIndex: 'releaseReason' },
        { title: t('field.consumedAt'), dataIndex: 'consumedAt', render: (d: string) => formatDateTime(d) },
      ]}
    />
  );
}

function RetroTab({ periodId }: { periodId: UUID }) {
  const { t } = useTranslation(NS);
  const q = useRetroRequests(periodId);
  return (
    <DataTable<RetroCandidate>
      data={q.data}
      isLoading={q.isLoading}
      error={q.error}
      columns={[
        { title: t('field.employee'), dataIndex: 'employmentId', render: (id: UUID) => <ShortId id={id} /> },
        { title: t('field.period'), key: 'period', render: (_: unknown, r: RetroCandidate) => range(r.periodStart, r.periodEnd) },
        { title: t('field.firstDate'), dataIndex: 'firstDate', render: (d: string) => formatDate(d) },
        { title: t('field.lastDate'), dataIndex: 'lastDate', render: (d: string) => formatDate(d) },
      ]}
    />
  );
}

/**
 * One attendance period: header, and tabs for the checklist, review / close / reopen (BR-ATT-074/079/087), a leaver's
 * early close, the published result versions, the payroll holds and the pending retro requests (BR-ATT-071).
 */
export function PeriodDetailPage({ periodId }: { periodId: UUID }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const q = usePeriod(periodId);
  const checklist = useChecklist(periodId);
  const actions = usePeriodActions(periodId);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [leaverId, setLeaverId] = useState<UUID | undefined>();
  const leaverChecklist = useChecklist(periodId, leaverId, !!leaverId);

  const p = q.data;
  if (q.isLoading) return <Skeleton active />;
  if (q.error || !p) {
    return <Result status="404" title={t('detail.notFound')} extra={q.error ? <ProblemAlert error={q.error} /> : null} />;
  }

  const openDialog = (d: Dialog) => {
    actions.close.reset();
    actions.reopen.reset();
    actions.earlyClose.reset();
    setDialog(d);
  };

  return (
    <div>
      <PageHeader
        title={
          <Space wrap>
            {t('detail.title', { from: formatDate(p.periodStart), to: formatDate(p.periodEnd) })}
            <AreaStatusTag status={p.status} />
          </Space>
        }
        breadcrumb={[{ label: t('detail.back'), to: '/attendance/periods' }, { label: range(p.periodStart, p.periodEnd) }]}
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: t('field.legalEntity'), value: <LegalEntityName id={p.legalEntityId} /> },
            { label: t('field.payGroup'), value: <ShortId id={p.payGroupId} /> },
            { label: t('field.period'), value: range(p.periodStart, p.periodEnd) },
            { label: t('field.otPeriod'), value: range(p.otPeriodStart, p.otPeriodEnd), hideEmpty: true },
            { label: t('field.stdDayMethod'), value: p.stdDayMethod ? t(`stdMethod.${p.stdDayMethod}`, { defaultValue: p.stdDayMethod }) : null },
            { label: t('field.stdDayValue'), value: p.stdDayValue ?? null, hideEmpty: true },
            { label: t('field.closeDeadline'), value: formatDate(p.closeDeadline) },
            { label: t('field.status'), value: <AreaStatusTag status={p.status} /> },
            { label: t('field.currentVersion'), value: p.currentVersion },
            { label: t('field.lockedVersion'), value: p.lockedVersion ?? null, hideEmpty: true },
            { label: t('field.closedAt'), value: formatDateTime(p.closedAt), hideEmpty: true },
            { label: t('field.reopenedAt'), value: formatDateTime(p.reopenedAt), hideEmpty: true },
            { label: t('field.reopenReason'), value: p.reopenReason, hideEmpty: true },
          ]}
        />
      </Card>

      <Tabs
        items={[
          { key: 'checklist', label: t('detail.tabChecklist'), children: <ChecklistTab period={p} /> },
          { key: 'close', label: t('detail.tabClose'), children: <CloseTab period={p} openDialog={openDialog} /> },
          {
            key: 'earlyClose',
            label: t('detail.tabEarlyClose'),
            children: <EarlyCloseTab period={p} employmentId={leaverId} setEmploymentId={setLeaverId} openDialog={openDialog} />,
          },
          { key: 'results', label: t('detail.tabResults'), children: <ResultsTab periodId={p.id} /> },
          { key: 'holds', label: t('detail.tabHolds'), children: <HoldsTab periodId={p.id} /> },
          { key: 'retro', label: t('detail.tabRetro'), children: <RetroTab periodId={p.id} /> },
        ]}
      />

      <CloseDialog
        open={dialog === 'close'}
        title={t('actions.closeTitle', { from: formatDate(p.periodStart), to: formatDate(p.periodEnd) })}
        hint={t('actions.closeHint')}
        okText={p.currentVersion > 0 ? t('actions.reclose') : t('actions.close')}
        checklist={checklist.data}
        checklistLoading={checklist.isLoading}
        onClose={() => setDialog(null)}
        pending={actions.close.isPending}
        error={actions.close.error}
        submit={async (body) => {
          const r = await actions.close.mutateAsync(body);
          fb.done(t('actions.closed', { version: r.version }));
        }}
      />
      <CloseDialog
        open={dialog === 'earlyClose'}
        title={t('actions.earlyClose')}
        hint={t('actions.earlyCloseHint')}
        okText={t('actions.earlyClose')}
        checklist={leaverChecklist.data}
        checklistLoading={leaverChecklist.isLoading}
        onClose={() => setDialog(null)}
        pending={actions.earlyClose.isPending}
        error={actions.earlyClose.error}
        submit={async (body) => {
          if (!leaverId) return;
          const r = await actions.earlyClose.mutateAsync({ employmentId: leaverId, body });
          fb.done(t('actions.earlyClosed', { version: r.version }));
        }}
      />
      <ReasonDialog
        open={dialog === 'reopen'}
        title={t('actions.reopenTitle')}
        hint={t('actions.reopenHint')}
        label={t('actions.reopenReason')}
        okText={t('actions.reopen')}
        onClose={() => setDialog(null)}
        pending={actions.reopen.isPending}
        error={actions.reopen.error}
        submit={async (reason) => {
          const o = await actions.reopen.mutateAsync(reason);
          fb.done(
            o.outcome === 'REOPENED'
              ? t('actions.reopened')
              : o.outcome === 'RELEASE_REQUESTED'
                ? t('actions.releaseRequested')
                : t('actions.approvalRequested'),
          );
        }}
      />
    </div>
  );
}
