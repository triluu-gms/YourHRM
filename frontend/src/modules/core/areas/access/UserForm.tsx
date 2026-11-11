import { useEffect } from 'react';
import { DatePicker, Form, Input } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { FormDrawer, useFeedback } from '@/shared/components';
import { DATE_TIME_FORMAT } from '@/shared/format';
import type { CreateUserRequest, UserView } from './api';
import { useCreateUser, useUpdateUser } from './queries';
import { applyFieldErrors, blankToNull, UUID_PATTERN } from './formErrors';
import './i18n';

interface FormValues {
  login: string;
  displayName?: string;
  email?: string;
  mobile?: string;
  locale?: string;
  identityId?: string;
  employmentId?: string;
  personId?: string;
  expiresAt?: Dayjs | null;
}

const LOCALE_PATTERN = /^[a-z]{2}(-[A-Z]{2})?$/;

/** Create (no `user`) or edit (`user` given, PUT with its version) drawer for a tenant user account. */
export function UserForm({
  open,
  onClose,
  user,
  onSaved,
}: {
  open: boolean;
  onClose: () => void;
  user?: UserView | null;
  onSaved?: (saved: UserView) => void;
}) {
  const { t } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateUser();
  const update = useUpdateUser(user?.id ?? '');
  const mutation = user ? update : create;

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    if (user) {
      form.setFieldsValue({
        login: user.login,
        displayName: user.displayName ?? undefined,
        email: user.email ?? undefined,
        mobile: user.mobile ?? undefined,
        locale: user.locale ?? undefined,
        identityId: user.identityId ?? undefined,
        employmentId: user.employmentId ?? undefined,
        personId: user.personId ?? undefined,
        expiresAt: user.expiresAt ? dayjs(user.expiresAt) : null,
      });
    }
  }, [open, user, form]);

  const submit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const { login, expiresAt, ...rest } = values;
    const body: CreateUserRequest = {
      login: login.trim(),
      ...blankToNull(rest),
      expiresAt: expiresAt ? expiresAt.toISOString() : null,
    };
    try {
      const saved = user
        ? await update.mutateAsync({ ...body, login: undefined, version: user.version } as never)
        : await create.mutateAsync(body);
      if (user) fb.saved();
      else fb.created();
      onSaved?.(saved);
      onClose();
    } catch (error) {
      if (!applyFieldErrors(form, error)) {
        /* other problems (409 stale-version, login-taken…) stay visible in the drawer via `error` */
      }
    }
  };

  const uuidRule = { pattern: UUID_PATTERN, message: ta('users.uuidInvalid') };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={user ? ta('users.edit') : ta('users.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
    >
      <Form<FormValues> form={form} layout="vertical" requiredMark="optional">
        <Form.Item
          name="login"
          label={ta('users.login')}
          rules={[{ required: true, whitespace: true, message: ta('users.loginRequired') }, { max: 150 }]}
        >
          <Input disabled={!!user} autoComplete="off" />
        </Form.Item>
        <Form.Item name="displayName" label={ta('users.displayName')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item
          name="email"
          label={ta('users.email')}
          rules={[{ type: 'email', message: ta('users.emailInvalid') }, { max: 254 }]}
        >
          <Input type="email" />
        </Form.Item>
        <Form.Item name="mobile" label={ta('users.mobile')} rules={[{ max: 32 }]}>
          <Input />
        </Form.Item>
        <Form.Item
          name="locale"
          label={ta('users.locale')}
          rules={[{ pattern: LOCALE_PATTERN, message: ta('users.localeInvalid') }]}
        >
          <Input placeholder={ta('users.localePlaceholder')} />
        </Form.Item>
        <Form.Item name="identityId" label={ta('users.identityId')} rules={[uuidRule]}>
          <Input style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="employmentId" label={ta('users.employmentId')} rules={[uuidRule]}>
          <Input style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="personId" label={ta('users.personId')} rules={[uuidRule]}>
          <Input style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item name="expiresAt" label={ta('users.expiresAt')} extra={t('common.optional')}>
          <DatePicker showTime format={DATE_TIME_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
