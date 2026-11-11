import { useState } from 'react';
import { Alert, Button, Card, Result, Skeleton, Space, Tag } from 'antd';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DescriptionList, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { formatDate, formatDateTime } from '@/shared/format';
import { LegalEntityName } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { LABOUR_CONTRACT_TYPES, type ContractTypeCode } from './api';
import {
  useCancelContract,
  useContract,
  useCreateSuspension,
  useGenerateContractDocument,
  useProbationResult,
  useSignContract,
  useTermsMutation,
} from './queries';
import {
  AreaStatusTag,
  ContractTypeLabel,
  DocumentLinkButton,
  followUpTerms,
  formatTerm,
  NS,
  ProbationDrawer,
  SignDrawer,
  SuspensionDrawer,
  TermsDrawer,
} from './components';
import { AppendicesCard } from './AppendicesCard';
import { SuspensionsTable } from './SuspensionsTable';

type Drawer = 'renew' | 'convert' | 'sign' | 'probation' | 'suspend' | null;

const RENEWABLE: ContractTypeCode[] = ['DEFINITE', 'SEASONAL'];
const CONVERTIBLE: ContractTypeCode[] = ['DEFINITE', 'SEASONAL'];

/**
 * One labour contract (US-PPL-047..056): terms, probation, document, paper signature, renew / convert, cancel,
 * appendices and suspensions. The backend enforces every legal rule; buttons only follow the obvious status rules.
 */
export function ContractDetailPage({ contractId }: { contractId: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const { t: tp } = useTranslation('people');
  const navigate = useNavigate();
  const fb = useFeedback();
  const q = useContract(contractId);
  const terms = useTermsMutation();
  const sign = useSignContract(contractId);
  const cancel = useCancelContract(contractId);
  const probation = useProbationResult(contractId);
  const document = useGenerateContractDocument(contractId);
  const suspend = useCreateSuspension(contractId);
  const [drawer, setDrawer] = useState<Drawer>(null);

  const c = q.data;
  if (q.isLoading) return <Skeleton active />;
  if (q.error || !c) {
    return <Result status="404" title={t('contracts.notFound')} extra={q.error ? <ProblemAlert error={q.error} /> : null} />;
  }

  const no = c.contractNo ?? t('contracts.draftNo');
  const unsigned = c.status === 'DRAFT' || c.status === 'PENDING_SIGNATURE';
  const live = c.status === 'ACTIVE' || c.status === 'SUSPENDED';
  const labour = LABOUR_CONTRACT_TYPES.includes(c.contractTypeCode);
  const probationOpen = !!c.probationEndDate && !c.probationResult && (live || unsigned);

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
      await cancel.mutateAsync(c.version);
      fb.done(t('contracts.cancelled'));
    } catch (e) {
      fb.failed(e);
    }
  };
  const openNew = (id: UUID) => void navigate({ to: '/people/contracts/$contractId', params: { contractId: id } });

  return (
    <div>
      <PageHeader
        title={
          <Space wrap>
            {t('contracts.detailTitle', { no })}
            <AreaStatusTag status={c.status} />
          </Space>
        }
        subTitle={
          <Space split="·" wrap>
            <ContractTypeLabel code={c.contractTypeCode} />
            <span>{formatTerm(c.startDate, c.endDate)}</span>
            {c.employeeNo ? <span>{c.employeeNo}</span> : null}
          </Space>
        }
        breadcrumb={[
          { label: c.employeeNo ?? t('field.employee'), to: `/people/employees/${c.employmentId}` },
          { label: no },
        ]}
        extra={
          <>
            <Link to="/people/employees/$employmentId" params={{ employmentId: c.employmentId }} search={{ tab: 'contracts' }}>
              <Button>{t('contracts.openEmployee')}</Button>
            </Link>
            <DocumentLinkButton documentId={c.documentId} size="middle" />
            <PermissionGate anyOf={P.CONTRACT_UPDATE}>
              {unsigned || live ? (
                <Button loading={document.isPending} onClick={() => void generate()}>
                  {t('contracts.generate')}
                </Button>
              ) : null}
              {unsigned ? (
                <Button type="primary" onClick={() => setDrawer('sign')}>
                  {t('contracts.sign')}
                </Button>
              ) : null}
              {probationOpen ? <Button onClick={() => setDrawer('probation')}>{t('contracts.recordProbation')}</Button> : null}
              {labour && c.status === 'ACTIVE' ? <Button onClick={() => setDrawer('suspend')}>{t('suspensions.create')}</Button> : null}
              {unsigned ? (
                <ConfirmButton danger title={t('contracts.confirmCancel')} loading={cancel.isPending} onConfirm={onCancel}>
                  {t('contracts.cancel')}
                </ConfirmButton>
              ) : null}
            </PermissionGate>
            <PermissionGate anyOf={P.CONTRACT_CREATE}>
              {live && RENEWABLE.includes(c.contractTypeCode) ? <Button onClick={() => setDrawer('renew')}>{t('contracts.renew')}</Button> : null}
              {live && CONVERTIBLE.includes(c.contractTypeCode) ? <Button onClick={() => setDrawer('convert')}>{t('contracts.convert')}</Button> : null}
            </PermissionGate>
          </>
        }
      />

      {c.autoConversionDueSince ? (
        <Alert type="warning" showIcon message={t('contracts.autoConversion', { date: formatDate(c.autoConversionDueSince) })} style={{ marginBottom: 16 }} />
      ) : null}

      <Card size="small" style={{ marginBottom: 16 }}>
        <DescriptionList
          items={[
            { label: t('field.contractNo'), value: c.contractNo },
            { label: tp('common.legalEntity'), value: <LegalEntityName id={c.legalEntityId} /> },
            { label: t('field.contractType'), value: <ContractTypeLabel code={c.contractTypeCode} /> },
            { label: t('field.definiteSeq'), value: c.definiteSeq, hideEmpty: true },
            { label: t('field.startDate'), value: formatDate(c.startDate) },
            { label: t('field.endDate'), value: formatDate(c.endDate) },
            { label: t('field.signDate'), value: formatDate(c.signDate) },
            { label: tc('field.status'), value: <AreaStatusTag status={c.status} /> },
            { label: t('field.signatoryName'), value: c.signatoryName },
            { label: t('field.signatoryTitle'), value: c.signatoryTitle },
            { label: t('field.probationDays'), value: c.probationDays, hideEmpty: true },
            { label: t('field.probationLevel'), value: c.probationLevelCode, hideEmpty: true },
            { label: t('field.probationEndDate'), value: formatDate(c.probationEndDate), hideEmpty: true },
            {
              label: t('field.probationResult'),
              hideEmpty: true,
              value: c.probationResult ? (
                <Space size={4}>
                  <Tag color={c.probationResult === 'PASS' ? 'green' : 'red'}>{t(`probationResult.${c.probationResult}`)}</Tag>
                  {formatDate(c.probationResultDate)}
                  {c.probationResultDecisionId ? (
                    <Link to="/people/decisions/$decisionId" params={{ decisionId: c.probationResultDecisionId }}>
                      {t('field.decision')}
                    </Link>
                  ) : null}
                </Space>
              ) : null,
            },
            {
              label: t('field.previousContract'),
              hideEmpty: true,
              value: c.previousContractId ? (
                <Link to="/people/contracts/$contractId" params={{ contractId: c.previousContractId }}>
                  {t('field.previousContract')}
                </Link>
              ) : null,
            },
            { label: t('field.definiteException'), value: c.definiteExceptionCode, hideEmpty: true },
            { label: t('field.definiteExceptionNote'), value: c.definiteExceptionNote, hideEmpty: true },
            { label: t('field.source'), value: c.source, hideEmpty: true },
            { label: tc('field.createdAt'), value: formatDateTime(c.createdAt) },
            { label: tc('field.version'), value: c.version },
          ]}
        />
      </Card>

      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {labour ? <AppendicesCard contract={c} /> : null}
        {labour ? <SuspensionsTable employmentId={c.employmentId} contractId={c.id} /> : null}
      </Space>

      <TermsDrawer
        open={drawer === 'renew'}
        title={t('contracts.renewTitle', { no })}
        initial={followUpTerms(c, c.contractTypeCode)}
        allowedTypes={['DEFINITE', 'SEASONAL', 'INDEFINITE']}
        onClose={() => setDrawer(null)}
        submit={async (body) => openNew((await terms.mutateAsync({ action: { kind: 'renew', contractId: c.id }, body })).id)}
        pending={terms.isPending}
        error={terms.error}
      />
      <TermsDrawer
        open={drawer === 'convert'}
        title={t('contracts.convertTitle', { no })}
        initial={followUpTerms(c, 'INDEFINITE')}
        allowedTypes={['INDEFINITE']}
        onClose={() => setDrawer(null)}
        submit={async (body) => openNew((await terms.mutateAsync({ action: { kind: 'convert', contractId: c.id }, body })).id)}
        pending={terms.isPending}
        error={terms.error}
      />
      <SignDrawer
        open={drawer === 'sign'}
        title={t('contracts.signTitle')}
        version={c.version}
        withSignDate
        onClose={() => setDrawer(null)}
        submit={(form) => sign.mutateAsync(form)}
        pending={sign.isPending}
        error={sign.error}
        successText={t('contracts.signed')}
      />
      <ProbationDrawer
        open={drawer === 'probation'}
        contract={c}
        onClose={() => setDrawer(null)}
        submit={(body) => probation.mutateAsync(body)}
        pending={probation.isPending}
        error={probation.error}
      />
      <SuspensionDrawer
        open={drawer === 'suspend'}
        contract={c}
        onClose={() => setDrawer(null)}
        submit={(body) => suspend.mutateAsync(body)}
        pending={suspend.isPending}
        error={suspend.error}
      />
    </div>
  );
}
