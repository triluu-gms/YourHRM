import { useEffect, useState } from 'react';
import { Button, Form, Input, Space, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { ConfirmButton, DataTable, FormDrawer, PageHeader, useFeedback } from '@/shared/components';
import { formatDate } from '@/shared/format';
import type { ResidencyStatus, ResidencyView } from './api';
import { PersonLink } from './PersonLink';
import { useDecideProposal, useResidencyProposals } from './queries';
import './i18n';

/** Payroll's tax residency proposals awaiting HR review (US-PPL-014): approve or reject with a reason. */
export function ResidencyProposalsPage() {
  const { t } = useTranslation();
  const { t: tc } = useTranslation('people-compliance');
  const fb = useFeedback();
  const query = useResidencyProposals();
  const decide = useDecideProposal();
  const [rejecting, setRejecting] = useState<ResidencyView | null>(null);

  const approve = async (p: ResidencyView) => {
    try {
      await decide.mutateAsync({ id: p.id, version: p.version });
      fb.done(tc('proposals.approved'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <PageHeader title={tc('proposals.title')} subTitle={tc('proposals.intro')} />
      <DataTable<ResidencyView>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: tc('proposals.empty') }}
        columns={[
          { title: tc('common.person'), key: 'person', render: (_, p) => <PersonLink personId={p.personId} /> },
          { title: tc('proposals.proposalRef'), dataIndex: 'proposalRef', render: (v: string | null) => v ?? '—' },
          {
            title: tc('residency.status'),
            dataIndex: 'status',
            render: (s: ResidencyStatus) => <Tag color={s === 'RESIDENT' ? 'green' : 'default'}>{tc(`residency.statuses.${s}`)}</Tag>,
          },
          { title: tc('residency.basis'), dataIndex: 'basis', render: (b: string) => tc(`residency.bases.${b}`, { defaultValue: b }) },
          { title: tc('residency.taxYear'), dataIndex: 'taxYear', render: (v: number | null) => v ?? '—' },
          { title: tc('residency.daysInVn'), dataIndex: 'daysInVn', align: 'right', render: (v: number | null) => v ?? '—' },
          { title: tc('common.validFrom'), dataIndex: 'validFrom', render: (v: string) => formatDate(v) },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, p) => (
              <Space size={0}>
                <ConfirmButton type="link" size="small" title={tc('proposals.approveConfirm')} onConfirm={() => approve(p)}>
                  {tc('proposals.approve')}
                </ConfirmButton>
                <Button type="link" size="small" danger onClick={() => setRejecting(p)}>
                  {tc('proposals.reject')}
                </Button>
              </Space>
            ),
          },
        ]}
      />
      <RejectDrawer proposal={rejecting} onClose={() => setRejecting(null)} />
    </div>
  );
}

function RejectDrawer({ proposal, onClose }: { proposal: ResidencyView | null; onClose: () => void }) {
  const { t: tc } = useTranslation('people-compliance');
  const fb = useFeedback();
  const [form] = Form.useForm<{ reason: string }>();
  const decide = useDecideProposal();

  useEffect(() => {
    if (!proposal) return;
    form.resetFields();
    decide.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [proposal, form]);

  const submit = async () => {
    if (!proposal) return;
    let v: { reason: string };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await decide.mutateAsync({ id: proposal.id, version: proposal.version, reject: v.reason.trim() });
      fb.done(tc('proposals.rejected'));
      onClose();
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <FormDrawer
      open={proposal !== null}
      onClose={onClose}
      title={tc('proposals.rejectTitle')}
      onSubmit={() => void submit()}
      submitting={decide.isPending}
      error={decide.error}
      submitLabel={tc('proposals.reject')}
      width={440}
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="reason"
          label={tc('proposals.reason')}
          rules={[{ required: true, whitespace: true, message: tc('proposals.reasonRequired') }, { max: 500 }]}
        >
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
