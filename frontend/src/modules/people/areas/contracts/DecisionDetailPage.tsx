import { useState } from 'react';
import { Button, Card, Result, Skeleton, Space } from 'antd';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DescriptionList, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { formatDate } from '@/shared/format';
import { LegalEntityName } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { useDecision, useDecisionActions } from './queries';
import { AreaStatusTag, DecisionDrawer, DocumentLinkButton, NS, SignDrawer } from './components';

/** One HR decision (BR-PPL-130, US-PPL-057/058): document, paper signature, cancel, reissue under a new number. */
export function DecisionDetailPage({ decisionId }: { decisionId: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const { t: tp } = useTranslation('people');
  const navigate = useNavigate();
  const fb = useFeedback();
  const q = useDecision(decisionId);
  const { document, sign, cancel, reissue } = useDecisionActions(decisionId);
  const [drawer, setDrawer] = useState<'sign' | 'reissue' | null>(null);

  const d = q.data;
  if (q.isLoading) return <Skeleton active />;
  if (q.error || !d) {
    return <Result status="404" title={t('decisions.notFound')} extra={q.error ? <ProblemAlert error={q.error} /> : null} />;
  }

  const no = d.decisionNo ?? t('contracts.draftNo');
  const signable = d.status === 'APPROVED' || d.status === 'DRAFT';
  const cancellable = d.status !== 'CANCELLED';

  const generate = async () => {
    try {
      const g = await document.mutateAsync();
      fb.done(t('contracts.generated', { no: g.documentNumber ?? '' }));
    } catch (e) {
      fb.failed(e);
    }
  };
  const onCancel = async () => {
    try {
      await cancel.mutateAsync(d.version);
      fb.done(t('decisions.cancelled'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <PageHeader
        title={
          <Space wrap>
            {t('decisions.detailTitle', { no })}
            <AreaStatusTag status={d.status} />
          </Space>
        }
        subTitle={t(`decisionType.${d.decisionTypeCode}`, { defaultValue: d.decisionTypeCode })}
        breadcrumb={d.employmentId ? [{ label: t('field.employee'), to: `/people/employees/${d.employmentId}` }, { label: no }] : [{ label: no }]}
        extra={
          <>
            {d.employmentId ? (
              <Link to="/people/employees/$employmentId" params={{ employmentId: d.employmentId }} search={{ tab: 'decisions' }}>
                <Button>{t('contracts.openEmployee')}</Button>
              </Link>
            ) : null}
            <DocumentLinkButton documentId={d.documentId} size="middle" />
            <PermissionGate anyOf={P.DECISION_UPDATE}>
              {cancellable ? (
                <Button loading={document.isPending} onClick={() => void generate()}>
                  {t('contracts.generate')}
                </Button>
              ) : null}
              {signable ? (
                <Button type="primary" onClick={() => setDrawer('sign')}>
                  {t('decisions.sign')}
                </Button>
              ) : null}
              {cancellable ? (
                <ConfirmButton danger title={t('decisions.confirmCancel')} loading={cancel.isPending} onConfirm={onCancel}>
                  {t('decisions.cancel')}
                </ConfirmButton>
              ) : null}
            </PermissionGate>
            {cancellable ? (
              <PermissionGate anyOf={P.DECISION_CREATE}>
                <Button onClick={() => setDrawer('reissue')}>{t('decisions.reissue')}</Button>
              </PermissionGate>
            ) : null}
          </>
        }
      />

      <Card size="small">
        <DescriptionList
          items={[
            { label: t('field.decisionNo'), value: d.decisionNo },
            { label: t('field.decisionType'), value: t(`decisionType.${d.decisionTypeCode}`, { defaultValue: d.decisionTypeCode }) },
            { label: tp('common.legalEntity'), value: <LegalEntityName id={d.legalEntityId} /> },
            { label: t('field.effectiveDate'), value: formatDate(d.effectiveDate) },
            { label: t('field.signDate'), value: formatDate(d.signDate) },
            { label: t('field.signatoryName'), value: d.signatoryName },
            { label: t('field.signatoryTitle'), value: d.signatoryTitle },
            { label: tc('field.status'), value: <AreaStatusTag status={d.status} /> },
            {
              label: t('decisions.workflow'),
              hideEmpty: true,
              value: d.workflowInstanceId ? <code>{d.workflowInstanceId}</code> : null,
            },
            { label: tc('field.version'), value: d.version },
          ]}
        />
      </Card>

      <SignDrawer
        open={drawer === 'sign'}
        title={t('decisions.sign')}
        version={d.version}
        withSignDate
        onClose={() => setDrawer(null)}
        submit={(form) => sign.mutateAsync(form)}
        pending={sign.isPending}
        error={sign.error}
        successText={t('decisions.signed')}
      />
      <DecisionDrawer
        open={drawer === 'reissue'}
        reissueOf={d}
        onClose={() => setDrawer(null)}
        reissue={async (body) => {
          const next = await reissue.mutateAsync(body);
          void navigate({ to: '/people/decisions/$decisionId', params: { decisionId: next.id } });
        }}
        pending={reissue.isPending}
        error={reissue.error}
      />
    </div>
  );
}
