import { useEffect } from 'react';
import { Form, Input } from 'antd';
import { useTranslation } from 'react-i18next';
import { FormDrawer, useFeedback } from '@/shared/components';
import type { RoleDetail } from './api';
import { useCopyRole, useCreateRole, useUpdateRole } from './queries';
import { applyFieldErrors } from './formErrors';
import './i18n';

interface FormValues {
  code: string;
  nameVi: string;
  nameEn: string;
}

/** Mirrors `RoleController` `@Pattern`. */
const ROLE_CODE_PATTERN = /^[A-Za-z0-9_.-]{2,64}$/;

export type RoleFormMode = { kind: 'create' } | { kind: 'edit'; role: RoleDetail } | { kind: 'copy'; role: RoleDetail };

/**
 * Role drawer: create an empty custom role, rename a custom role (PUT with its version), or copy any role
 * (system ones included) into a new custom role with the same matrix.
 */
export function RoleForm({
  mode,
  onClose,
  onSaved,
}: {
  mode: RoleFormMode | null;
  onClose: () => void;
  onSaved?: (saved: RoleDetail) => void;
}) {
  const { t: ta } = useTranslation('core-access');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const source = mode && mode.kind !== 'create' ? mode.role : null;
  const create = useCreateRole();
  const update = useUpdateRole(source?.id ?? '');
  const copy = useCopyRole(source?.id ?? '');
  const mutation = mode?.kind === 'edit' ? update : mode?.kind === 'copy' ? copy : create;

  useEffect(() => {
    if (!mode) return;
    form.resetFields();
    create.reset();
    update.reset();
    copy.reset();
    if (mode.kind === 'edit') {
      form.setFieldsValue({ code: mode.role.code, nameVi: mode.role.nameVi, nameEn: mode.role.nameEn ?? '' });
    } else if (mode.kind === 'copy') {
      form.setFieldsValue({
        code: `${mode.role.code}_COPY`.slice(0, 64),
        nameVi: mode.role.nameVi,
        nameEn: mode.role.nameEn ?? '',
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [mode, form]);

  const submit = async () => {
    if (!mode) return;
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const body = { code: values.code.trim(), nameVi: values.nameVi.trim(), nameEn: values.nameEn.trim() };
    try {
      let saved: RoleDetail;
      if (mode.kind === 'edit') {
        saved = await update.mutateAsync({ nameVi: body.nameVi, nameEn: body.nameEn, version: mode.role.version });
        fb.saved();
      } else if (mode.kind === 'copy') {
        saved = await copy.mutateAsync(body);
        fb.done(ta('roles.copied'));
      } else {
        saved = await create.mutateAsync({ ...body, permissions: [] });
        fb.created();
      }
      onSaved?.(saved);
      onClose();
    } catch (error) {
      applyFieldErrors(form, error); // anything else (role-code-taken, stale-version…) shows in the drawer
    }
  };

  const title =
    mode?.kind === 'edit' ? ta('roles.edit') : mode?.kind === 'copy' ? ta('roles.copyTitle', { code: mode.role.code }) : ta('roles.create');

  return (
    <FormDrawer
      open={mode !== null}
      onClose={onClose}
      title={title}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={480}
    >
      <Form<FormValues> form={form} layout="vertical">
        <Form.Item
          name="code"
          label={ta('roles.code')}
          extra={mode?.kind === 'edit' ? undefined : ta('roles.codeHint')}
          rules={[
            { required: true, whitespace: true, message: ta('roles.codeRequired') },
            { pattern: ROLE_CODE_PATTERN, message: ta('roles.codeInvalid') },
          ]}
        >
          <Input disabled={mode?.kind === 'edit'} autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item
          name="nameVi"
          label={ta('roles.nameVi')}
          rules={[{ required: true, whitespace: true, message: ta('roles.nameViRequired') }, { max: 200 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          name="nameEn"
          label={ta('roles.nameEn')}
          rules={[{ required: true, whitespace: true, message: ta('roles.nameEnRequired') }, { max: 200 }]}
        >
          <Input />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
