import { useState } from 'react';
import { Button, Card, Space, Tag } from 'antd';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, useFeedback } from '@/shared/components';
import { formatDate, today } from '@/shared/format';
import { PeoplePermissions as P } from '../../permissions';
import type { SuspensionView } from './api';
import { useSuspensionActions, useSuspensions } from './queries';
import { AreaStatusTag, DateDrawer, NS } from './components';

/**
 * Contract suspensions of an employment (US-PPL-054, Art. 30–31): reason, period, legal return deadline, the
 * CONTRACT_SUSPENSION decision; resume (return date) and cancel. `contractId` narrows to one contract.
 */
export function SuspensionsTable({ employmentId, contractId }: { employmentId: UUID; contractId?: UUID }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const q = useSuspensions(employmentId);
  const rows = (q.data ?? []).filter((s) => !contractId || s.contractId === contractId);

  return (
    <Card size="small" title={t('suspensions.title')}>
      <DataTable<SuspensionView>
        size="small"
        data={rows}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          {
            title: t('field.contractNo'),
            dataIndex: 'contractNo',
            render: (no: string | null, s) => (
              <Link to="/people/contracts/$contractId" params={{ contractId: s.contractId }}>
                {no ?? t('contracts.draftNo')}
              </Link>
            ),
          },
          { title: t('field.reason'), dataIndex: 'reasonCode', render: (r: string) => t(`suspensionReason.${r}`, { defaultValue: r }) },
          { title: t('field.validFrom'), dataIndex: 'validFrom', render: (v: string) => formatDate(v) },
          { title: t('field.plannedEndDate'), dataIndex: 'plannedEndDate', render: (v: string | null) => formatDate(v) || '—' },
          {
            title: t('field.returnDate'),
            key: 'return',
            render: (_, s) =>
              s.returnDate ? (
                formatDate(s.returnDate)
              ) : s.returnDueDate ? (
                <Space size={4}>
                  {`≤ ${formatDate(s.returnDueDate)}`}
                  {s.status === 'ACTIVE' && s.returnDueDate < today() ? <Tag color="red">{t('suspensions.overdue')}</Tag> : null}
                </Space>
              ) : (
                '—'
              ),
          },
          {
            title: t('field.decision'),
            key: 'decision',
            render: (_, s) =>
              s.decisionId ? (
                <Link to="/people/decisions/$decisionId" params={{ decisionId: s.decisionId }}>
                  {s.decisionNo ?? t('contracts.draftNo')}
                </Link>
              ) : (
                '—'
              ),
          },
          { title: tc('field.status'), dataIndex: 'status', render: (s: string) => <AreaStatusTag status={s} /> },
          { title: tc('field.actions'), key: 'actions', render: (_, s) => <SuspensionActions suspension={s} /> },
        ]}
      />
    </Card>
  );
}

function SuspensionActions({ suspension: s }: { suspension: SuspensionView }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [resuming, setResuming] = useState(false);
  const { resume, cancel } = useSuspensionActions(s.id);
  const open = s.status === 'ACTIVE' || s.status === 'APPROVED' || s.status === 'DRAFT';
  if (!open) return null;

  const onCancel = async () => {
    try {
      await cancel.mutateAsync(s.version);
      fb.done(t('suspensions.cancelled'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <PermissionGate anyOf={P.CONTRACT_UPDATE}>
      <Space size={0}>
        {s.status !== 'DRAFT' ? (
          <Button type="link" size="small" onClick={() => setResuming(true)}>
            {t('suspensions.resume')}
          </Button>
        ) : null}
        <ConfirmButton type="link" size="small" danger title={t('suspensions.confirmCancel')} loading={cancel.isPending} onConfirm={onCancel}>
          {t('suspensions.cancel')}
        </ConfirmButton>
      </Space>
      <DateDrawer
        open={resuming}
        title={t('suspensions.resumeTitle')}
        label={t('field.returnDate')}
        onClose={() => setResuming(false)}
        submit={async (date) => {
          await resume.mutateAsync({ returnDate: date as string, version: s.version });
          fb.done(t('suspensions.resumed'));
        }}
        pending={resume.isPending}
        error={resume.error}
      />
    </PermissionGate>
  );
}
