import { useEffect, useState } from 'react';
import { Button, DatePicker, Form, Input, Select, Space, Typography } from 'antd';
import { CalendarOutlined } from '@ant-design/icons';
import { Link, useNavigate } from '@tanstack/react-router';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { applyFieldErrors, LegalEntityName, LegalEntitySelect, toIsoDate, UUID_PATTERN } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { SEPARATION_STATUSES, type SeparationStatus, type SeparationView } from './api';
import { NS, SeparationStatusTag, useLabel } from './parts';
import { useContractExpirySeparation, useSeparations } from './queries';

/** Separations in scope (US-PPL-060..067): status, legal entity and termination-date filters. */
export function SeparationsListPage() {
  const { t } = useTranslation(NS);
  const { t: tp } = useTranslation('people');
  const label = useLabel();
  const navigate = useNavigate();
  const [status, setStatus] = useState<SeparationStatus | undefined>();
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [expiryOpen, setExpiryOpen] = useState(false);
  const query = useSeparations({
    status,
    legalEntityId,
    from: toIsoDate(range?.[0]) ?? undefined,
    to: toIsoDate(range?.[1]) ?? undefined,
  });

  return (
    <div>
      <PageHeader
        title={t('list.title')}
        extra={
          <PermissionGate anyOf={P.SEPARATION_CREATE}>
            <Button icon={<CalendarOutlined />} onClick={() => setExpiryOpen(true)}>
              {t('list.contractExpiry')}
            </Button>
          </PermissionGate>
        }
      >
        <Space wrap>
          <Select<SeparationStatus>
            allowClear
            aria-label={t('field.status')}
            placeholder={t('list.allStatuses')}
            style={{ width: 200 }}
            value={status}
            onChange={setStatus}
            options={SEPARATION_STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))}
          />
          <LegalEntitySelect
            aria-label={tp('common.legalEntity')}
            placeholder={t('list.allLegalEntities')}
            style={{ width: 260 }}
            value={legalEntityId}
            onChange={setLegalEntityId}
          />
          <Space>
            <Typography.Text type="secondary">{t('list.terminationRange')}</Typography.Text>
            <DatePicker.RangePicker format={DATE_FORMAT} allowEmpty={[true, true]} value={range} onChange={(v) => setRange(v)} />
          </Space>
        </Space>
      </PageHeader>

      <DataTable<SeparationView>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        onRow={(row) => ({
          style: { cursor: 'pointer' },
          onClick: () => void navigate({ to: '/people/separations/$separationId', params: { separationId: row.id } }),
        })}
        columns={[
          {
            title: tp('common.employeeNo'),
            dataIndex: 'employeeNo',
            render: (no: string, row) => (
              <Link to="/people/separations/$separationId" params={{ separationId: row.id }} onClick={(e) => e.stopPropagation()}>
                {no}
              </Link>
            ),
          },
          { title: tp('common.fullName'), dataIndex: 'fullName' },
          { title: tp('common.legalEntity'), dataIndex: 'legalEntityId', render: (id: UUID | null) => <LegalEntityName id={id} /> },
          { title: t('field.separationType'), dataIndex: 'separationType', render: (v: string | null) => label('type', v) },
          { title: t('field.status'), dataIndex: 'status', render: (s: SeparationStatus) => <SeparationStatusTag status={s} /> },
          { title: t('field.lastWorkingDate'), dataIndex: 'lastWorkingDate', render: (v: string | null) => formatDate(v) },
          { title: t('field.terminationDate'), dataIndex: 'terminationDate', render: (v: string | null) => formatDate(v) },
          { title: t('field.severanceType'), dataIndex: 'severanceType', render: (v: string | null) => label('severanceType', v) },
        ]}
      />

      <ContractExpiryDrawer open={expiryOpen} onClose={() => setExpiryOpen(false)} />
    </div>
  );
}

interface ExpiryValues {
  contractId: string;
  noticeDate?: Dayjs | null;
}

/** Opens a separation for a fixed-term contract that ends without renewal (POST /separations/contract-expiry). */
function ContractExpiryDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const navigate = useNavigate();
  const [form] = Form.useForm<ExpiryValues>();
  const run = useContractExpirySeparation();

  useEffect(() => {
    if (open) {
      form.resetFields();
      run.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset on open only
  }, [open, form]);

  const submit = async () => {
    let v: ExpiryValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const res = await run.mutateAsync({ contractId: v.contractId.trim(), noticeDate: toIsoDate(v.noticeDate) });
      fb.done(t('contractExpiry.created', { name: res.separation.fullName }));
      onClose();
      void navigate({ to: '/people/separations/$separationId', params: { separationId: res.separation.id } });
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={t('contractExpiry.title')} width={440} onSubmit={() => void submit()} submitting={run.isPending} error={run.error}>
      <Typography.Paragraph type="secondary">{t('contractExpiry.hint')}</Typography.Paragraph>
      <Form<ExpiryValues> form={form} layout="vertical">
        <Form.Item name="contractId" label={t('field.contractId')} rules={[{ required: true }, { pattern: UUID_PATTERN }]}>
          <Input style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="noticeDate" label={t('field.noticeDate')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
