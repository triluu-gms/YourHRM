import { useEffect, useState } from 'react';
import { Alert, Button, DatePicker, Form, Input, InputNumber, Select, Tag } from 'antd';
import { PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { Dayjs } from 'dayjs';
import { PermissionGate } from '@/shared/auth';
import { DataTable, FormDrawer, MoneyInput, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatMoney, formatValidity } from '@/shared/format';
import { applyFieldErrors, BankSelect, blankToNull, MaskedValue, toIsoDate } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { SPLIT_TYPES, type BankAccountView, type BankWarning, type SplitType } from './api';
import { useMyBankAccounts, useRequestBankAccount } from './queries';
import './i18n';

const STATUS_KEYS = ['PENDING', 'ACTIVE', 'CLOSED', 'REJECTED'];

/** "My bank accounts" (US-PPL-075): own salary accounts (masked); a change is a request with step-up, approved by HR. */
export function MyBankAccountsPage() {
  const { t } = useTranslation();
  const { t: te } = useTranslation('people-ess');
  const query = useMyBankAccounts();
  const [open, setOpen] = useState(false);
  const [warnings, setWarnings] = useState<BankWarning[]>([]);
  const statusLabels = Object.fromEntries(STATUS_KEYS.map((s) => [s, te(`bank.status.${s}`)]));
  const hasPending = (query.data ?? []).some((a) => a.status === 'PENDING');

  return (
    <div>
      <PageHeader
        title={te('bank.title')}
        extra={
          <PermissionGate anyOf={P.ESS_REQUEST}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
              {te('bank.request')}
            </Button>
          </PermissionGate>
        }
      />
      <Alert type="info" showIcon message={te('requestNote')} style={{ marginBottom: 16 }} />
      {hasPending ? <Alert type="warning" showIcon message={te('pending')} style={{ marginBottom: 16 }} /> : null}
      {warnings.length > 0 ? (
        <Alert
          type="warning"
          showIcon
          closable
          onClose={() => setWarnings([])}
          message={te('bank.warnings')}
          description={
            <ul style={{ margin: 0, paddingLeft: 16 }}>
              {warnings.map((w) => (
                <li key={w.code}>{t(`error.${w.code}`, { defaultValue: w.message ?? w.code })}</li>
              ))}
            </ul>
          }
          style={{ marginBottom: 16 }}
        />
      ) : null}

      <DataTable<BankAccountView>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: te('bank.bank'), key: 'bank', render: (_, a) => a.bankName ?? a.bankCode },
          { title: te('bank.accountNo'), dataIndex: 'accountNoMasked', render: (v: string | null) => <MaskedValue value={v} /> },
          { title: te('bank.holderName'), dataIndex: 'holderName' },
          {
            title: te('bank.split'),
            key: 'split',
            render: (_, a) =>
              a.splitType
                ? `${te(`bank.splitTypes.${a.splitType}`)}${a.splitValue != null ? ` · ${a.splitType === 'PERCENT' ? `${a.splitValue}%` : formatMoney(a.splitValue, a.currencyCode ?? 'VND')}` : ''}`
                : '—',
          },
          { title: te('bank.priority'), dataIndex: 'priority', align: 'right' },
          {
            title: te('bank.validity'),
            key: 'validity',
            render: (_, a) => (a.validFrom ? formatValidity(a.validFrom, a.validTo, t('common.openEnded')) : '—'),
          },
          {
            title: t('field.status'),
            key: 'status',
            render: (_, a) => (
              <>
                <StatusTag status={a.status} labels={statusLabels} />
                {a.stepUpVerified ? (
                  <Tag icon={<SafetyCertificateOutlined />} color="blue">
                    {te('bank.verified')}
                  </Tag>
                ) : null}
              </>
            ),
          },
        ]}
      />

      <BankRequestDrawer open={open} onClose={() => setOpen(false)} onWarnings={setWarnings} />
    </div>
  );
}

interface FormValues {
  bankCode: string;
  branchCode?: string;
  accountNo: string;
  holderName: string;
  splitType: SplitType;
  splitValue?: number | null;
  priority?: number | null;
  validFrom?: Dayjs | null;
}

function BankRequestDrawer({
  open,
  onClose,
  onWarnings,
}: {
  open: boolean;
  onClose: () => void;
  onWarnings: (w: BankWarning[]) => void;
}) {
  const { t } = useTranslation();
  const { t: te } = useTranslation('people-ess');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const request = useRequestBankAccount();
  const splitType = Form.useWatch('splitType', form);

  useEffect(() => {
    if (open) {
      form.resetFields();
      request.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer opens
  }, [open, form]);

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      const saved = await request.mutateAsync({
        ...blankToNull({ bankCode: v.bankCode, branchCode: v.branchCode ?? '', holderName: v.holderName.trim() }),
        accountNo: v.accountNo.replace(/\s/g, ''),
        splitType: v.splitType,
        splitValue: v.splitType === 'REMAINDER' ? null : (v.splitValue ?? null),
        priority: v.priority ?? null,
        validFrom: toIsoDate(v.validFrom),
      });
      fb.done(te('bank.requested'));
      onWarnings(saved.warnings ?? []);
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={te('bank.requestTitle')}
      onSubmit={() => void submit()}
      submitting={request.isPending}
      error={request.error}
      submitLabel={te('dependants.submit')}
      width={480}
    >
      <Alert type="info" showIcon message={te('bank.stepUpHint')} style={{ marginBottom: 16 }} />
      <Form<FormValues> form={form} layout="vertical" initialValues={{ splitType: 'REMAINDER' }}>
        <Form.Item name="bankCode" label={te('bank.bank')} rules={[{ required: true, message: te('bank.bankRequired') }]}>
          <BankSelect />
        </Form.Item>
        <Form.Item name="branchCode" label={te('bank.branchCode')} rules={[{ max: 32 }]}>
          <Input />
        </Form.Item>
        <Form.Item
          name="accountNo"
          label={te('bank.accountNo')}
          rules={[
            { required: true, whitespace: true, message: te('bank.accountNoRequired') },
            { pattern: /^[0-9 ]{1,40}$/, message: te('bank.accountNoInvalid') },
          ]}
        >
          <Input autoComplete="off" inputMode="numeric" />
        </Form.Item>
        <Form.Item
          name="holderName"
          label={te('bank.holderName')}
          extra={te('bank.holderHint')}
          rules={[{ required: true, whitespace: true, message: te('bank.holderNameRequired') }, { max: 200 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item name="splitType" label={te('bank.split')}>
          <Select options={SPLIT_TYPES.map((s) => ({ value: s, label: te(`bank.splitTypes.${s}`) }))} />
        </Form.Item>
        {splitType && splitType !== 'REMAINDER' ? (
          <Form.Item
            name="splitValue"
            label={te('bank.splitValue')}
            rules={[{ required: true, message: te('bank.splitValueRequired') }]}
          >
            {splitType === 'PERCENT' ? <InputNumber min={0} max={100} addonAfter="%" style={{ width: '100%' }} /> : <MoneyInput />}
          </Form.Item>
        ) : null}
        <Form.Item name="priority" label={te('bank.priority')}>
          <InputNumber min={1} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="validFrom" label={te('bank.validFrom')} extra={t('common.optional')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
