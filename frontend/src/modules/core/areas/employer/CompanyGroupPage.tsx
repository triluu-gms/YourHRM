import { useState } from 'react';
import { Button, Card, Form, Input, Result, Select, Skeleton } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DescriptionList, FormDrawer, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { formatDateTime } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { CompanyGroup } from './api';
import { useCompanyGroup, useUpdateCompanyGroup } from './queries';
import { applyFieldErrors, blankToNull, UUID_PATTERN } from './formErrors';
import { EMPLOYER_NS } from './i18n';

const LOCALES = ['vi-VN', 'en-US'];

/** The tenant's company profile (BR-CORE-001, US-CORE-001): read, and edit with optimistic locking. */
export function CompanyGroupPage() {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const query = useCompanyGroup();
  const [editing, setEditing] = useState(false);
  const group = query.data;

  if (query.isLoading) return <Skeleton active />;
  if (query.error || !group) return <Result status="warning" title={te('companyGroup.title')} extra={<ProblemAlert error={query.error} />} />;

  return (
    <div>
      <PageHeader
        title={te('companyGroup.title')}
        subTitle={te('companyGroup.subtitle')}
        extra={
          <PermissionGate anyOf={P.COMPANY_GROUP_UPDATE}>
            <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>
              {te('companyGroup.edit')}
            </Button>
          </PermissionGate>
        }
      />
      <Card>
        <DescriptionList
          items={[
            { label: te('companyGroup.name'), value: group.name },
            { label: te('companyGroup.defaultLocale'), value: te(`companyGroup.locale.${group.defaultLocale}`, { defaultValue: group.defaultLocale }) },
            { label: te('companyGroup.timeZone'), value: group.timeZone },
            { label: te('companyGroup.currencyCode'), value: group.currencyCode },
            { label: te('companyGroup.logo'), value: group.logoDocumentId ? <code>{group.logoDocumentId}</code> : null },
            { label: te('companyGroup.updatedAt'), value: formatDateTime(group.updatedAt) },
            { label: t('field.version'), value: group.version },
          ]}
        />
      </Card>
      {editing ? <CompanyGroupForm group={group} onClose={() => setEditing(false)} /> : null}
    </div>
  );
}

type FormValues = Omit<CompanyGroup, 'id' | 'updatedAt' | 'updatedBy' | 'version'>;

/** Mounted only while open, so `initialValues` is the whole reset logic. */
function CompanyGroupForm({ group, onClose }: { group: CompanyGroup; onClose: () => void }) {
  const { t: te } = useTranslation(EMPLOYER_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const update = useUpdateCompanyGroup();

  const submit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      const clean = blankToNull(values);
      await update.mutateAsync({ ...clean, currencyCode: clean.currencyCode.toUpperCase(), version: group.version });
      fb.saved();
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={te('companyGroup.edit')}
      onSubmit={() => void submit()}
      submitting={update.isPending}
      error={update.error}
      width={520}
    >
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={{
          name: group.name,
          logoDocumentId: group.logoDocumentId ?? undefined,
          defaultLocale: group.defaultLocale,
          timeZone: group.timeZone,
          currencyCode: group.currencyCode,
        }}
      >
        <Form.Item name="name" label={te('companyGroup.name')} rules={[{ required: true, whitespace: true, message: te('companyGroup.nameRequired') }, { max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="defaultLocale" label={te('companyGroup.defaultLocale')} rules={[{ required: true }]}>
          <Select options={LOCALES.map((l) => ({ value: l, label: te(`companyGroup.locale.${l}`) }))} />
        </Form.Item>
        <Form.Item name="timeZone" label={te('companyGroup.timeZone')} rules={[{ required: true, whitespace: true }]}>
          <Input placeholder="Asia/Ho_Chi_Minh" />
        </Form.Item>
        <Form.Item
          name="currencyCode"
          label={te('companyGroup.currencyCode')}
          rules={[{ required: true }, { pattern: /^[A-Za-z]{3}$/, message: te('companyGroup.currencyInvalid') }]}
        >
          <Input maxLength={3} style={{ width: 120, textTransform: 'uppercase' }} />
        </Form.Item>
        <Form.Item
          name="logoDocumentId"
          label={te('companyGroup.logo')}
          extra={te('companyGroup.logoHint')}
          rules={[{ pattern: UUID_PATTERN, message: te('common.uuidInvalid') }]}
        >
          <Input placeholder={te('common.uuidHint')} style={{ fontFamily: 'monospace' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
