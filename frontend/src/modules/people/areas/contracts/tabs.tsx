import { useState } from 'react';
import { Alert, Button, Card, Space, Tag, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { isProblem } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, DescriptionList, ProblemAlert } from '@/shared/components';
import { formatDate } from '@/shared/format';
import type { ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { contractsApi, type ContractView, type DecisionView } from './api';
import { contractKeys, useContracts, useDecisions, useIssueDecision, useTermsMutation } from './queries';
import { AreaStatusTag, ContractTypeLabel, DecisionDrawer, DocumentLinkButton, followUpTerms, formatTerm, NS, TermsDrawer } from './components';
import { SuspensionsTable } from './SuspensionsTable';

/** Contracts of the employment (US-PPL-047/048): the one in force on the profile date, history, suspensions. */
export function ContractsTab({ employmentId, profile }: ProfileTabProps) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [creating, setCreating] = useState(false);
  const list = useContracts(employmentId);
  const terms = useTermsMutation();
  const current = useQuery({
    queryKey: contractKeys.current(employmentId, profile.asOf),
    queryFn: () => contractsApi.current(employmentId, profile.asOf),
    retry: false,
  });
  const latest = (list.data ?? []).filter((c) => c.status !== 'CANCELLED').sort((a, b) => b.startDate.localeCompare(a.startDate))[0];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        size="small"
        title={t('contracts.current')}
        extra={
          <PermissionGate anyOf={P.CONTRACT_CREATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {t('contracts.create')}
            </Button>
          </PermissionGate>
        }
      >
        {current.data ? (
          <CurrentContract contract={current.data} />
        ) : current.error && !(isProblem(current.error) && current.error.status === 404) ? (
          <ProblemAlert error={current.error} />
        ) : current.isLoading ? null : (
          <Typography.Text type="secondary">{t('contracts.noCurrent')}</Typography.Text>
        )}
      </Card>

      <DataTable<ContractView>
        size="small"
        data={list.data}
        isLoading={list.isLoading}
        error={list.error}
        columns={[
          {
            title: t('field.contractNo'),
            dataIndex: 'contractNo',
            render: (no: string | null, c) => (
              <Link to="/people/contracts/$contractId" params={{ contractId: c.id }}>
                {no ?? t('contracts.draftNo')}
              </Link>
            ),
          },
          { title: t('field.contractType'), dataIndex: 'contractTypeCode', render: (code: string) => <ContractTypeLabel code={code} /> },
          { title: t('field.term'), key: 'term', render: (_, c) => formatTerm(c.startDate, c.endDate) },
          { title: t('field.definiteSeq'), dataIndex: 'definiteSeq', render: (n: number | null) => n ?? '—' },
          { title: t('field.signDate'), dataIndex: 'signDate', render: (v: string | null) => formatDate(v) || '—' },
          {
            title: t('contracts.probation'),
            key: 'probation',
            render: (_, c) =>
              c.probationEndDate ? (
                <Space size={4}>
                  {formatDate(c.probationEndDate)}
                  {c.probationResult ? (
                    <Tag color={c.probationResult === 'PASS' ? 'green' : 'red'}>{t(`probationResult.${c.probationResult}`)}</Tag>
                  ) : null}
                </Space>
              ) : (
                '—'
              ),
          },
          { title: t('field.document'), dataIndex: 'documentId', render: (id: string | null) => <DocumentLinkButton documentId={id} /> },
          { title: tc('field.status'), key: 'status', render: (_, c) => <AreaStatusTag status={c.status} /> },
        ]}
      />

      <SuspensionsTable employmentId={employmentId} />

      <TermsDrawer
        open={creating}
        title={t('contracts.create')}
        initial={followUpTerms(latest)}
        onClose={() => setCreating(false)}
        submit={(body) => terms.mutateAsync({ action: { kind: 'create', employmentId }, body })}
        pending={terms.isPending}
        error={terms.error}
      />
    </Space>
  );
}

function CurrentContract({ contract: c }: { contract: ContractView }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  return (
    <>
      {c.autoConversionDueSince ? (
        <Alert type="warning" showIcon message={t('contracts.autoConversion', { date: formatDate(c.autoConversionDueSince) })} style={{ marginBottom: 12 }} />
      ) : null}
      <DescriptionList
        items={[
          {
            label: t('field.contractNo'),
            value: (
              <Link to="/people/contracts/$contractId" params={{ contractId: c.id }}>
                {c.contractNo ?? t('contracts.draftNo')}
              </Link>
            ),
          },
          { label: t('field.contractType'), value: <ContractTypeLabel code={c.contractTypeCode} /> },
          { label: t('field.term'), value: formatTerm(c.startDate, c.endDate) },
          { label: t('field.probationEndDate'), value: formatDate(c.probationEndDate), hideEmpty: true },
          { label: t('field.signDate'), value: formatDate(c.signDate), hideEmpty: true },
          { label: tc('field.status'), value: <AreaStatusTag status={c.status} /> },
        ]}
      />
    </>
  );
}

/** HR decisions of the employment (BR-PPL-130, US-PPL-057/058). */
export function DecisionsTab({ employmentId }: ProfileTabProps) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [issuing, setIssuing] = useState(false);
  const list = useDecisions(employmentId);
  const issue = useIssueDecision();
  const rows = [...(list.data ?? [])].sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate));

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
        <PermissionGate anyOf={P.DECISION_CREATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setIssuing(true)}>
            {t('decisions.create')}
          </Button>
        </PermissionGate>
      </Space>
      <DataTable<DecisionView>
        size="small"
        data={rows}
        isLoading={list.isLoading}
        error={list.error}
        columns={[
          {
            title: t('field.decisionNo'),
            dataIndex: 'decisionNo',
            render: (no: string | null, d) => (
              <Link to="/people/decisions/$decisionId" params={{ decisionId: d.id }}>
                {no ?? t('contracts.draftNo')}
              </Link>
            ),
          },
          { title: t('field.decisionType'), dataIndex: 'decisionTypeCode', render: (c: string) => t(`decisionType.${c}`, { defaultValue: c }) },
          { title: t('field.effectiveDate'), dataIndex: 'effectiveDate', render: (v: string) => formatDate(v) },
          { title: t('field.signDate'), dataIndex: 'signDate', render: (v: string | null) => formatDate(v) || '—' },
          { title: t('field.signatoryName'), dataIndex: 'signatoryName' },
          { title: t('field.document'), dataIndex: 'documentId', render: (id: string | null) => <DocumentLinkButton documentId={id} /> },
          { title: tc('field.status'), key: 'status', render: (_, d) => <AreaStatusTag status={d.status} /> },
        ]}
      />
      <DecisionDrawer
        open={issuing}
        employmentId={employmentId}
        onClose={() => setIssuing(false)}
        issue={(body) => issue.mutateAsync(body)}
        pending={issue.isPending}
        error={issue.error}
      />
    </Space>
  );
}
