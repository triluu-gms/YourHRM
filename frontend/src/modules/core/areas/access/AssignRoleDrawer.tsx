import { useEffect, useMemo } from 'react';
import { Checkbox, DatePicker, Form, Select } from 'antd';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { FormDrawer, useFeedback } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE, localizedName } from '@/shared/format';
import {
  SCOPE_TYPES,
  scopeRequiresIds,
  type AssignmentScopeType,
  type LegalEntityOption,
  type LocationOption,
  type OrgUnitOption,
} from './api';
import { useGrantRole, useRoles, useScopeLookup } from './queries';
import { applyFieldErrors } from './formErrors';
import './i18n';

interface FormValues {
  roleId: UUID;
  scopeType: AssignmentScopeType;
  scopeIds?: UUID[];
  includeSubtree?: boolean;
  validFrom?: Dayjs | null;
  validTo?: Dayjs | null;
}

/** "Assign role" drawer: role, scope type, scope objects (looked up per kind), subtree flag, validity. */
export function AssignRoleDrawer({ userId, open, onClose }: { userId: UUID; open: boolean; onClose: () => void }) {
  const { t, i18n } = useTranslation();
  const { t: ta } = useTranslation('core-access');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const roles = useRoles();
  const grant = useGrantRole(userId);
  const scopeType = Form.useWatch('scopeType', form);
  const needsIds = scopeRequiresIds(scopeType);
  const lookup = useScopeLookup(needsIds ? (scopeType as 'LEGAL_ENTITY' | 'LOCATION' | 'ORG_UNIT') : null);

  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  const scopeOptions = useMemo(() => {
    if (!lookup.data) return [];
    if (scopeType === 'LEGAL_ENTITY') {
      return (lookup.data as LegalEntityOption[]).map((le) => ({
        value: le.id,
        label: `${le.code} – ${le.current?.shortName || le.current?.legalNameVi || le.current?.legalNameEn || ''}`.trim(),
      }));
    }
    if (scopeType === 'LOCATION') {
      return (lookup.data as LocationOption[]).map((l) => ({ value: l.id, label: `${l.code} – ${l.current?.name ?? ''}`.trim() }));
    }
    return (lookup.data as OrgUnitOption[]).map((ou) => ({
      value: ou.id,
      label: `${'· '.repeat(ou.depth ?? 0)}${ou.code} – ${localizedName(ou, i18n.language)}`.trim(),
    }));
  }, [lookup.data, scopeType, i18n.language]);

  const submit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await grant.mutateAsync({
        roleId: values.roleId,
        scopeType: values.scopeType,
        scopeIds: scopeRequiresIds(values.scopeType) ? (values.scopeIds ?? []) : [],
        includeSubtree: values.scopeType === 'ORG_UNIT' ? !!values.includeSubtree : false,
        validFrom: values.validFrom ? values.validFrom.format(ISO_DATE) : null,
        validTo: values.validTo ? values.validTo.format(ISO_DATE) : null,
      });
      fb.done(ta('assignments.granted'));
      onClose();
    } catch (error) {
      applyFieldErrors(form, error);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={ta('assignments.assign')}
      onSubmit={() => void submit()}
      submitting={grant.isPending}
      error={grant.error}
      width={480}
    >
      <Form<FormValues> form={form} layout="vertical" initialValues={{ scopeType: 'TENANT', includeSubtree: true }}>
        <Form.Item name="roleId" label={ta('assignments.role')} rules={[{ required: true, message: ta('assignments.roleRequired') }]}>
          <Select
            showSearch
            optionFilterProp="label"
            loading={roles.isLoading}
            placeholder={ta('assignments.rolePlaceholder')}
            options={(roles.data ?? []).map((r) => ({ value: r.id, label: `${r.code} – ${localizedName(r, i18n.language)}` }))}
          />
        </Form.Item>
        <Form.Item
          name="scopeType"
          label={ta('assignments.scopeType')}
          rules={[{ required: true, message: ta('assignments.scopeTypeRequired') }]}
        >
          <Select
            options={SCOPE_TYPES.map((s) => ({ value: s, label: ta(`assignments.scope.${s}`) }))}
            onChange={() => form.setFieldValue('scopeIds', [])}
          />
        </Form.Item>
        {needsIds ? (
          <Form.Item
            name="scopeIds"
            label={ta('assignments.scopeIds')}
            rules={[{ required: true, type: 'array', min: 1, message: ta('assignments.scopeIdsRequired') }]}
          >
            <Select
              mode="multiple"
              showSearch
              optionFilterProp="label"
              loading={lookup.isLoading}
              placeholder={ta('assignments.scopeIdsPlaceholder')}
              options={scopeOptions}
            />
          </Form.Item>
        ) : null}
        {scopeType === 'ORG_UNIT' ? (
          <Form.Item name="includeSubtree" valuePropName="checked">
            <Checkbox>{ta('assignments.includeSubtree')}</Checkbox>
          </Form.Item>
        ) : null}
        <Form.Item name="validFrom" label={ta('assignments.validFrom')} extra={t('common.optional')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="validTo" label={ta('assignments.validTo')} extra={t('common.optional')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
