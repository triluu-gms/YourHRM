import { useState } from 'react';
import { Button, Card, Space } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, useFeedback } from '@/shared/components';
import { formatDate } from '@/shared/format';
import { PeoplePermissions as P } from '../../permissions';
import { LABOUR_CONTRACT_TYPES, type AppendixView, type ContractView } from './api';
import { useAppendices, useAppendixActions, useCreateAppendix } from './queries';
import { AppendixDrawer, AreaStatusTag, ChangesList, DocumentLinkButton, NS, SignDrawer } from './components';

/** Appendices of a labour contract (US-PPL-053, BR-PPL-055): amend pay, job, place or time — never the term. */
export function AppendicesCard({ contract }: { contract: ContractView }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [creating, setCreating] = useState(false);
  const q = useAppendices(contract.id);
  const create = useCreateAppendix(contract.id);
  const canAmend =
    LABOUR_CONTRACT_TYPES.includes(contract.contractTypeCode) && (contract.status === 'ACTIVE' || contract.status === 'SUSPENDED');

  return (
    <Card
      size="small"
      title={t('appendices.title')}
      extra={
        canAmend ? (
          <PermissionGate anyOf={P.CONTRACT_UPDATE}>
            <Button icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              {t('appendices.create')}
            </Button>
          </PermissionGate>
        ) : null
      }
    >
      <DataTable<AppendixView>
        size="small"
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        columns={[
          { title: t('field.appendixNo'), dataIndex: 'appendixNo', render: (no: string | null) => no ?? t('contracts.draftNo') },
          { title: t('field.effectiveDate'), dataIndex: 'effectiveDate', render: (v: string) => formatDate(v) },
          { title: t('field.changes'), key: 'changes', render: (_, a) => <ChangesList changes={a.changes} masked={a.payTermsMasked} /> },
          { title: t('field.document'), dataIndex: 'documentId', render: (id: string | null) => <DocumentLinkButton documentId={id} /> },
          { title: tc('field.status'), dataIndex: 'status', render: (s: string) => <AreaStatusTag status={s} /> },
          { title: tc('field.actions'), key: 'actions', render: (_, a) => <AppendixActions appendix={a} /> },
        ]}
      />
      <AppendixDrawer
        open={creating}
        contract={contract}
        onClose={() => setCreating(false)}
        submit={(body) => create.mutateAsync(body)}
        pending={create.isPending}
        error={create.error}
      />
    </Card>
  );
}

function AppendixActions({ appendix: a }: { appendix: AppendixView }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [signing, setSigning] = useState(false);
  const { sign, cancel, document } = useAppendixActions(a.id);
  if (a.status === 'SIGNED' || a.status === 'CANCELLED') return null;

  const run = async (fn: () => Promise<unknown>, done: string) => {
    try {
      await fn();
      fb.done(done);
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <PermissionGate anyOf={P.CONTRACT_UPDATE}>
      <Space size={0} wrap>
        <Button
          type="link"
          size="small"
          loading={document.isPending}
          onClick={() => void run(() => document.mutateAsync(), t('contracts.generate'))}
        >
          {t('contracts.generate')}
        </Button>
        <Button type="link" size="small" onClick={() => setSigning(true)}>
          {t('appendices.sign')}
        </Button>
        <ConfirmButton
          type="link"
          size="small"
          danger
          title={t('appendices.confirmCancel')}
          loading={cancel.isPending}
          onConfirm={() => run(() => cancel.mutateAsync(a.version), t('appendices.cancelled'))}
        >
          {t('appendices.cancel')}
        </ConfirmButton>
      </Space>
      <SignDrawer
        open={signing}
        title={t('appendices.sign')}
        version={a.version}
        withSignDate={false}
        onClose={() => setSigning(false)}
        submit={(form) => sign.mutateAsync(form)}
        pending={sign.isPending}
        error={sign.error}
        successText={t('appendices.signed')}
      />
    </PermissionGate>
  );
}
