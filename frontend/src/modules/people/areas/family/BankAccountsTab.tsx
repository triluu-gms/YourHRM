import { useEffect, useState } from 'react';
import { Alert, Button, DatePicker, Form, Input, InputNumber, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { EyeOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, FormDrawer, MoneyInput, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatMoney } from '@/shared/format';
import { applyFieldErrors, BankSelect, MaskedValue, toIsoDate, type ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { SPLIT_TYPES, type BankAccountSaved, type BankAccountView, type SplitType } from './api';
import { useBankAccounts, useCloseBankAccount, useCreateBankAccount, useRevealAccountNumber } from './queries';
import './i18n';

/**
 * Salary bank accounts (BR-PPL-015, US-PPL-031/032): masked numbers (P3), reveal on demand (audited, needs
 * `hr.person.view_sensitive`), new accounts go to approval, close with a last valid date.
 */
export function BankAccountsTab({ personId, profile }: ProfileTabProps) {
  const { t } = useTranslation();
  const { t: tf } = useTranslation('people-family');
  const fb = useFeedback();
  const query = useBankAccounts(personId);
  const reveal = useRevealAccountNumber(personId);
  const [creating, setCreating] = useState(false);
  const [closing, setClosing] = useState<BankAccountView | null>(null);
  const [revealed, setRevealed] = useState<Record<UUID, string>>({});
  const [warnings, setWarnings] = useState<BankAccountSaved['warnings']>([]);

  const onReveal = async (id: UUID) => {
    try {
      const n = await reveal.mutateAsync(id);
      setRevealed((r) => ({ ...r, [id]: n.accountNo }));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <Space style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {tf('bank.title')}
        </Typography.Title>
        <PermissionGate anyOf={P.BANK_ACCOUNT_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            {tf('bank.add')}
          </Button>
        </PermissionGate>
      </Space>
      {warnings.length > 0 ? (
        <Alert
          type="warning"
          showIcon
          closable
          onClose={() => setWarnings([])}
          message={tf('bank.warnings')}
          description={warnings.map((w) => w.message).join(' · ')}
          style={{ marginBottom: 12 }}
        />
      ) : null}

      <DataTable<BankAccountView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: tf('bank.bank'), key: 'bank', render: (_, a) => a.bankName ?? a.bankCode },
          {
            title: tf('bank.accountNo'),
            key: 'accountNo',
            render: (_, a) => (
              <Space size={4}>
                {revealed[a.id] ? <Typography.Text code>{revealed[a.id]}</Typography.Text> : <MaskedValue value={a.accountNoMasked} />}
                {a.stepUpVerified ? (
                  <Tooltip title={tf('bank.stepUp')}>
                    <SafetyCertificateOutlined style={{ color: '#52c41a' }} />
                  </Tooltip>
                ) : null}
              </Space>
            ),
          },
          { title: tf('bank.holderName'), dataIndex: 'holderName' },
          {
            title: tf('bank.split'),
            key: 'split',
            render: (_, a) =>
              a.splitType === 'PERCENT'
                ? `${a.splitValue ?? 0}%`
                : a.splitType === 'FIXED'
                  ? formatMoney(a.splitValue, a.currencyCode ?? 'VND')
                  : tf('bank.splitTypes.REMAINDER'),
          },
          { title: tf('bank.priority'), dataIndex: 'priority', align: 'right' },
          { title: tf('bank.validFrom'), dataIndex: 'validFrom', render: (v: string) => formatDate(v) },
          { title: tf('bank.validTo'), dataIndex: 'validTo', render: (v: string | null) => formatDate(v) || '—' },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, a) => (
              <Space size={0} wrap>
                {!revealed[a.id] ? (
                  <PermissionGate anyOf={P.PERSON_VIEW_SENSITIVE}>
                    <ConfirmButton type="link" size="small" icon={<EyeOutlined />} title={tf('bank.revealConfirm')} onConfirm={() => onReveal(a.id)}>
                      {tf('bank.reveal')}
                    </ConfirmButton>
                  </PermissionGate>
                ) : null}
                {a.status === 'ACTIVE' && !a.validTo ? (
                  <PermissionGate anyOf={P.BANK_ACCOUNT_UPDATE}>
                    <Button type="link" size="small" danger onClick={() => setClosing(a)}>
                      {tf('bank.close')}
                    </Button>
                  </PermissionGate>
                ) : null}
              </Space>
            ),
          },
        ]}
      />

      <BankAccountDrawer
        personId={personId}
        defaultHolder={profile.person.fullName}
        open={creating}
        onClose={() => setCreating(false)}
        onSaved={(s) => setWarnings(s.warnings)}
      />
      <CloseBankAccountDrawer personId={personId} account={closing} onClose={() => setClosing(null)} />
    </div>
  );
}

interface AccountValues {
  bankCode: string;
  branchCode?: string;
  accountNo: string;
  holderName: string;
  splitType: SplitType;
  splitValue?: number | null;
  priority?: number | null;
  validFrom: Dayjs;
  validTo?: Dayjs | null;
}

function BankAccountDrawer({
  personId,
  defaultHolder,
  open,
  onClose,
  onSaved,
}: {
  personId: UUID;
  defaultHolder: string;
  open: boolean;
  onClose: () => void;
  onSaved: (saved: BankAccountSaved) => void;
}) {
  const { t: tf } = useTranslation('people-family');
  const fb = useFeedback();
  const [form] = Form.useForm<AccountValues>();
  const create = useCreateBankAccount(personId);
  const splitType = Form.useWatch('splitType', form);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    create.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, form]);

  const submit = async () => {
    let v: AccountValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const saved = await create.mutateAsync({
        bankCode: v.bankCode,
        branchCode: v.branchCode?.trim() || null,
        accountNo: v.accountNo.replace(/\s+/g, ''),
        holderName: v.holderName.trim(),
        currencyCode: 'VND',
        splitType: v.splitType,
        splitValue: v.splitType === 'REMAINDER' ? null : (v.splitValue ?? null),
        priority: v.priority ?? null,
        validFrom: toIsoDate(v.validFrom) as string,
        validTo: toIsoDate(v.validTo),
      });
      fb.done(tf('bank.submitted'));
      onSaved(saved);
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={tf('bank.add')} onSubmit={() => void submit()} submitting={create.isPending} error={create.error}>
      <Form<AccountValues> form={form} layout="vertical" initialValues={{ splitType: 'REMAINDER', holderName: defaultHolder }}>
        <Form.Item name="bankCode" label={tf('bank.bank')} rules={[{ required: true, message: tf('bank.bankRequired') }]}>
          <BankSelect />
        </Form.Item>
        <Form.Item name="branchCode" label={tf('bank.branchCode')} rules={[{ max: 32 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="accountNo" label={tf('bank.accountNo')} rules={[{ required: true, whitespace: true, message: tf('bank.accountRequired') }, { max: 40 }]}>
          <Input autoComplete="off" inputMode="numeric" />
        </Form.Item>
        <Form.Item name="holderName" label={tf('bank.holderName')} rules={[{ required: true, whitespace: true, message: tf('bank.holderRequired') }, { max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="splitType" label={tf('bank.splitType')}>
          <Select options={SPLIT_TYPES.map((s) => ({ value: s, label: tf(`bank.splitTypes.${s}`) }))} />
        </Form.Item>
        {splitType === 'PERCENT' ? (
          <Form.Item name="splitValue" label={tf('bank.splitValue')} rules={[{ required: true, message: tf('bank.splitValueRequired') }]}>
            <InputNumber min={0.01} max={100} addonAfter="%" style={{ width: '100%' }} />
          </Form.Item>
        ) : null}
        {splitType === 'FIXED' ? (
          <Form.Item name="splitValue" label={tf('bank.splitValue')} rules={[{ required: true, message: tf('bank.splitValueRequired') }]}>
            <MoneyInput />
          </Form.Item>
        ) : null}
        <Form.Item name="priority" label={tf('bank.priority')}>
          <InputNumber min={1} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Space wrap>
          <Form.Item name="validFrom" label={tf('bank.validFrom')} rules={[{ required: true, message: tf('bank.validFromRequired') }]}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="validTo" label={tf('bank.validTo')}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
        </Space>
      </Form>
    </FormDrawer>
  );
}

function CloseBankAccountDrawer({ personId, account, onClose }: { personId: UUID; account: BankAccountView | null; onClose: () => void }) {
  const { t: tf } = useTranslation('people-family');
  const fb = useFeedback();
  const [form] = Form.useForm<{ validTo: Dayjs }>();
  const close = useCloseBankAccount(personId);

  useEffect(() => {
    if (!account) return;
    form.resetFields();
    close.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [account, form]);

  const submit = async () => {
    if (!account) return;
    let v: { validTo: Dayjs };
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      await close.mutateAsync({ id: account.id, validTo: toIsoDate(v.validTo) as string, version: account.version });
      fb.done(tf('bank.closed'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={account !== null}
      onClose={onClose}
      title={tf('bank.closeTitle', { account: account?.accountNoMasked ?? '' })}
      onSubmit={() => void submit()}
      submitting={close.isPending}
      error={close.error}
      width={420}
    >
      {account ? (
        <Tag style={{ marginBottom: 16 }}>
          {account.bankName ?? account.bankCode} · {account.accountNoMasked}
        </Tag>
      ) : null}
      <Form form={form} layout="vertical">
        <Form.Item name="validTo" label={tf('bank.closeDate')} rules={[{ required: true, message: tf('bank.validFromRequired') }]}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
