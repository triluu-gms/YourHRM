import { useEffect, useState } from 'react';
import { Button, Checkbox, Form, Input, Select, Space, Tag } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, FormDrawer, useFeedback } from '@/shared/components';
import { applyFieldErrors, type ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { CONTACT_TYPES, type ContactType, type ContactView } from './api';
import { useAddContact, useChangeContact, useContacts, useRemoveContact } from './queries';
import { PATTERNS } from './shared';
import './i18n';

const isEmail = (t: ContactType | undefined) => t === 'PERSONAL_EMAIL' || t === 'WORK_EMAIL';
const isPhone = (t: ContactType | undefined) => t === 'MOBILE' || t === 'HOME_PHONE' || t === 'WORK_PHONE';

/** Phones and e-mails of the person (US-PPL-006): one primary per type; the primary personal e-mail is the OD-8 login. */
export function ContactsTab({ personId, profile }: ProfileTabProps) {
  const { t } = useTranslation('people-person');
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [editing, setEditing] = useState<ContactView | 'new' | null>(null);
  const query = useContacts(personId);
  const remove = useRemoveContact(personId);
  const readOnly = profile.person.anonymised;

  const onDelete = async (id: string) => {
    try {
      await remove.mutateAsync(id);
      fb.done(t('contacts.deleted'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      {!readOnly ? (
        <PermissionGate anyOf={P.PERSON_UPDATE}>
          <div style={{ textAlign: 'right', marginBottom: 12 }}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>
              {t('contacts.add')}
            </Button>
          </div>
        </PermissionGate>
      ) : null}
      <DataTable<ContactView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: t('contacts.type'), dataIndex: 'contactType', render: (v: ContactType) => t(`contacts.types.${v}`, { defaultValue: v }) },
          { title: t('contacts.value'), dataIndex: 'value' },
          {
            title: t('contacts.primary'),
            key: 'flags',
            render: (_, c) => (
              <Space size={4} wrap>
                {c.primary ? <Tag color="blue">{t('contacts.primary')}</Tag> : null}
                {c.verified ? <Tag color="green">{t('contacts.verified')}</Tag> : null}
                {c.usableForFormerEmployeeLogin ? <Tag color="purple">{t('contacts.formerEmployeeLogin')}</Tag> : null}
              </Space>
            ),
          },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_, c) =>
              readOnly ? null : (
                <PermissionGate anyOf={P.PERSON_UPDATE}>
                  <Space size={0}>
                    <Button type="link" size="small" onClick={() => setEditing(c)}>
                      {tc('action.edit')}
                    </Button>
                    <ConfirmButton type="link" size="small" danger title={t('contacts.confirmDelete')} onConfirm={() => onDelete(c.id)}>
                      {tc('action.delete')}
                    </ConfirmButton>
                  </Space>
                </PermissionGate>
              ),
          },
        ]}
      />
      <ContactDrawer personId={personId} editing={editing} onClose={() => setEditing(null)} />
    </div>
  );
}

interface ContactValues {
  contactType: ContactType;
  value: string;
  primary?: boolean;
}

function ContactDrawer({ personId, editing, onClose }: { personId: string; editing: ContactView | 'new' | null; onClose: () => void }) {
  const { t } = useTranslation('people-person');
  const fb = useFeedback();
  const [form] = Form.useForm<ContactValues>();
  const add = useAddContact(personId);
  const change = useChangeContact(personId);
  const existing = editing && editing !== 'new' ? editing : null;
  const mutation = existing ? change : add;
  const type = Form.useWatch('contactType', form);

  useEffect(() => {
    if (!editing) return;
    form.resetFields();
    add.reset();
    change.reset();
    if (existing) form.setFieldsValue({ contactType: existing.contactType, value: existing.value, primary: existing.primary });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [editing, form]);

  const submit = async () => {
    let v: ContactValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    try {
      if (existing) await change.mutateAsync({ contactId: existing.id, version: existing.version, value: v.value.trim(), primary: !!v.primary });
      else await add.mutateAsync({ contactType: v.contactType, value: v.value.trim(), primary: !!v.primary });
      fb.saved(t('contacts.saved'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const valueRules = [
    { required: true, whitespace: true, message: t('contacts.valueRequired') },
    { max: 254 },
    ...(isEmail(type) ? [{ pattern: PATTERNS.EMAIL, message: t('contacts.emailInvalid') }] : []),
    ...(isPhone(type) ? [{ pattern: PATTERNS.PHONE, message: t('contacts.phoneInvalid') }] : []),
  ];

  return (
    <FormDrawer
      open={editing !== null}
      onClose={onClose}
      title={existing ? t('contacts.edit') : t('contacts.add')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={440}
    >
      <Form<ContactValues> form={form} layout="vertical" initialValues={{ primary: false }}>
        <Form.Item name="contactType" label={t('contacts.type')} rules={[{ required: true, message: t('contacts.typeRequired') }]}>
          <Select disabled={!!existing} options={CONTACT_TYPES.map((v) => ({ value: v, label: t(`contacts.types.${v}`) }))} />
        </Form.Item>
        <Form.Item name="value" label={t('contacts.value')} rules={valueRules}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="primary" valuePropName="checked">
          <Checkbox>{t('contacts.primary')}</Checkbox>
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
