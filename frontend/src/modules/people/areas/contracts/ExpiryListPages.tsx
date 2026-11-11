import { useState } from 'react';
import { Button, InputNumber, Space, Tag, Typography } from 'antd';
import { Link, useNavigate } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, PageHeader, useFeedback } from '@/shared/components';
import { formatDate } from '@/shared/format';
import { LegalEntityName, LegalEntitySelect } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import type { EndingProbation, ExpiringContract } from './api';
import { useExpiringContracts, useProbationsEnding, useTerminateAtExpiry } from './queries';
import { AreaStatusTag, ContractTypeLabel, DateDrawer, NS } from './components';

function Filters({
  withinDays,
  setWithinDays,
  legalEntityId,
  setLegalEntityId,
}: {
  withinDays: number;
  setWithinDays: (n: number) => void;
  legalEntityId?: UUID;
  setLegalEntityId: (id?: UUID) => void;
}) {
  const { t } = useTranslation(NS);
  const { t: tp } = useTranslation('people');
  return (
    <Space wrap>
      <Space.Compact>
        <Button disabled>{t('field.withinDays')}</Button>
        <InputNumber
          aria-label={t('field.withinDays')}
          min={0}
          max={366}
          value={withinDays}
          onChange={(v) => setWithinDays(typeof v === 'number' ? v : 0)}
        />
      </Space.Compact>
      <LegalEntitySelect
        aria-label={tp('common.legalEntity')}
        placeholder={tp('directory.allLegalEntities')}
        style={{ width: 260 }}
        value={legalEntityId}
        onChange={setLegalEntityId}
      />
    </Space>
  );
}

const daysTag = (n: number) => <Tag color={n < 0 ? 'red' : n <= 15 ? 'orange' : 'blue'}>{n}</Tag>;

/** Contracts ending within N days (US-PPL-052/054): the follow-up, auto-conversion and the "terminate at expiry" action. */
export function ExpiringContractsPage() {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const { t: tp } = useTranslation('people');
  const [withinDays, setWithinDays] = useState(60);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [terminating, setTerminating] = useState<ExpiringContract | null>(null);
  const q = useExpiringContracts({ withinDays, legalEntityId });
  const terminate = useTerminateAtExpiry();
  const fb = useFeedback();
  const navigate = useNavigate();

  return (
    <div>
      <PageHeader title={t('expiring.title')} subTitle={t('expiring.intro')}>
        <Filters withinDays={withinDays} setWithinDays={setWithinDays} legalEntityId={legalEntityId} setLegalEntityId={setLegalEntityId} />
      </PageHeader>
      <DataTable<ExpiringContract>
        rowKey="contractId"
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          {
            title: tp('common.employeeNo'),
            dataIndex: 'employeeNo',
            render: (no: string | null, r) => (
              <Link to="/people/employees/$employmentId" params={{ employmentId: r.employmentId }} search={{ tab: 'contracts' }}>
                {no ?? r.employmentId}
              </Link>
            ),
          },
          { title: tp('common.legalEntity'), dataIndex: 'legalEntityId', render: (id: UUID | null) => <LegalEntityName id={id} /> },
          {
            title: t('field.contractNo'),
            dataIndex: 'contractNo',
            render: (no: string | null, r) => (
              <Link to="/people/contracts/$contractId" params={{ contractId: r.contractId }}>
                {no ?? t('contracts.draftNo')}
              </Link>
            ),
          },
          {
            title: t('field.contractType'),
            key: 'type',
            render: (_, r) => (
              <>
                <ContractTypeLabel code={r.contractTypeCode} />
                {r.definiteSeq ? ` (${r.definiteSeq})` : ''}
              </>
            ),
          },
          { title: t('field.endDate'), dataIndex: 'endDate', render: (v: string) => formatDate(v) },
          { title: t('field.daysLeft'), dataIndex: 'daysLeft', align: 'right', render: daysTag },
          { title: tc('field.status'), dataIndex: 'status', render: (s: string) => <AreaStatusTag status={s} /> },
          {
            title: t('field.followingContract'),
            key: 'follow',
            render: (_, r) => (
              <Space direction="vertical" size={0}>
                {r.followingContractId ? (
                  <Link to="/people/contracts/$contractId" params={{ contractId: r.followingContractId }}>
                    {t('expiring.renewed')}
                  </Link>
                ) : (
                  <Typography.Text type="secondary">{t('expiring.noFollowUp')}</Typography.Text>
                )}
                {r.nextMustBeIndefinite ? <Tag color="purple">{t('contracts.nextMustBeIndefinite')}</Tag> : null}
                {r.autoConversionDue && r.autoConversionDueSince ? (
                  <Tag color="red">{t('contracts.autoConversion', { date: formatDate(r.autoConversionDueSince) })}</Tag>
                ) : null}
              </Space>
            ),
          },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_, r) =>
              r.followingContractId ? null : (
                <PermissionGate anyOf={P.SEPARATION_CREATE}>
                  <Button size="small" onClick={() => setTerminating(r)}>
                    {t('expiring.terminate')}
                  </Button>
                </PermissionGate>
              ),
          },
        ]}
      />
      <DateDrawer
        open={terminating !== null}
        title={t('expiring.terminateTitle', { no: terminating?.contractNo ?? '' })}
        label={t('field.noticeDate')}
        required={false}
        hint={t('expiring.terminateHint')}
        onClose={() => setTerminating(null)}
        submit={async (noticeDate) => {
          if (!terminating) return;
          const res = await terminate.mutateAsync({ contractId: terminating.contractId, noticeDate });
          fb.done(t('expiring.terminated'));
          void navigate({ to: '/people/separations/$separationId', params: { separationId: res.separation.id } });
        }}
        pending={terminate.isPending}
        error={terminate.error}
      />
    </div>
  );
}

/** Probations ending within N days (US-PPL-049): record the result from the contract page before the end. */
export function ProbationsEndingPage() {
  const { t } = useTranslation(NS);
  const { t: tp } = useTranslation('people');
  const [withinDays, setWithinDays] = useState(30);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const q = useProbationsEnding({ withinDays, legalEntityId });

  return (
    <div>
      <PageHeader title={t('probations.title')} subTitle={t('probations.intro')}>
        <Filters withinDays={withinDays} setWithinDays={setWithinDays} legalEntityId={legalEntityId} setLegalEntityId={setLegalEntityId} />
      </PageHeader>
      <DataTable<EndingProbation>
        rowKey="contractId"
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          {
            title: tp('common.employeeNo'),
            dataIndex: 'employeeNo',
            render: (no: string | null, r) => (
              <Link to="/people/employees/$employmentId" params={{ employmentId: r.employmentId }} search={{ tab: 'contracts' }}>
                {no ?? r.employmentId}
              </Link>
            ),
          },
          { title: tp('common.legalEntity'), dataIndex: 'legalEntityId', render: (id: UUID | null) => <LegalEntityName id={id} /> },
          {
            title: t('field.contractNo'),
            dataIndex: 'contractNo',
            render: (no: string | null, r) => (
              <Link to="/people/contracts/$contractId" params={{ contractId: r.contractId }}>
                {no ?? t('contracts.draftNo')}
              </Link>
            ),
          },
          { title: t('field.contractType'), dataIndex: 'contractTypeCode', render: (c: string) => <ContractTypeLabel code={c} /> },
          { title: t('field.probationEndDate'), dataIndex: 'probationEndDate', render: (v: string) => formatDate(v) },
          { title: t('field.daysLeft'), dataIndex: 'daysLeft', align: 'right', render: daysTag },
          {
            title: '',
            key: 'go',
            render: (_, r) => (
              <PermissionGate anyOf={P.CONTRACT_UPDATE}>
                <Link to="/people/contracts/$contractId" params={{ contractId: r.contractId }}>
                  <Button size="small">{t('contracts.recordProbation')}</Button>
                </Link>
              </PermissionGate>
            ),
          },
        ]}
      />
    </div>
  );
}
