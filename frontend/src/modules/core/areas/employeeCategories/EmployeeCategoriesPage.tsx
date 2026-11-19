import { useState } from 'react';
import { Button, Checkbox, Form, Input, InputNumber, Space, Switch, Tag, Typography } from 'antd';
import { CheckOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { isProblem } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, PageHeader, StatusTag, useFeedback } from '@/shared/components';
import { CorePermissions as P } from '../../permissions';
import {
  CATEGORY_CODE_PATTERN,
  CATEGORY_KINDS,
  NO_FLAGS,
  type CategoryKind,
  type EmployeeCategory,
  type EmployeeCategoryFlags,
} from './api';
import { useCreateEmployeeCategory, useEmployeeCategories, useUpdateEmployeeCategory } from './queries';
import { EMPLOYEE_CATEGORIES_NS } from './i18n';

/**
 * Employee categories (BR-CORE-026, US-CORE-023): the tenant-wide code list, pre-seeded at provisioning. Create,
 * rename, reorder and (de)activate — a category is never deleted because records reference it.
 */
export function EmployeeCategoriesPage() {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYEE_CATEGORIES_NS);
  const [includeInactive, setIncludeInactive] = useState(false);
  const [form, setForm] = useState<{ category: EmployeeCategory | null } | null>(null);
  const canUpdate = usePermission(P.EMPLOYEE_CATEGORY_UPDATE);
  const query = useEmployeeCategories(includeInactive);

  return (
    <div>
      <PageHeader title={te('page.title')} subTitle={te('page.subtitle')} />

      <Space wrap style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Checkbox checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)}>
          {te('page.includeInactive')}
        </Checkbox>
        <PermissionGate anyOf={P.EMPLOYEE_CATEGORY_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ category: null })}>
            {te('page.create')}
          </Button>
        </PermissionGate>
      </Space>

      <DataTable<EmployeeCategory>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: te('page.empty') }}
        columns={[
          { title: te('page.sortOrder'), dataIndex: 'sortOrder', width: 90, align: 'right' },
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: t('field.nameVi'), dataIndex: 'nameVi' },
          { title: t('field.nameEn'), dataIndex: 'nameEn' },
          {
            title: te('page.flags'),
            key: 'flags',
            render: (_: unknown, row: EmployeeCategory) => <FlagTags flags={row.flags} />,
          },
          {
            title: te('page.headcount'),
            key: 'headcount',
            align: 'center',
            render: (_: unknown, row: EmployeeCategory) =>
              row.flags.countsInHeadcount ? <CheckOutlined aria-label={te('flag.countsInHeadcount')} /> : null,
          },
          {
            title: t('field.status'),
            dataIndex: 'active',
            render: (a: boolean) => <StatusTag status={a ? 'ACTIVE' : 'INACTIVE'} />,
          },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, row: EmployeeCategory) => (
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      aria-label={`${t('action.edit')} ${row.code}`}
                      onClick={() => setForm({ category: row })}
                    />
                  ),
                },
              ]
            : []),
        ]}
      />

      {form ? <EmployeeCategoryForm category={form.category} onClose={() => setForm(null)} /> : null}
    </div>
  );
}

/** The kinds a category carries; `countsInHeadcount` has its own column. */
function FlagTags({ flags }: { flags: EmployeeCategoryFlags }) {
  const { t: te } = useTranslation(EMPLOYEE_CATEGORIES_NS);
  const kinds = CATEGORY_KINDS.filter((k) => flags[k]);
  if (kinds.length === 0) return <Typography.Text type="secondary">{te('page.none')}</Typography.Text>;
  return (
    <Space size={[4, 4]} wrap>
      {kinds.map((k) => (
        <Tag key={k}>{te(`flag.${k}`)}</Tag>
      ))}
    </Space>
  );
}

interface FormValues extends EmployeeCategoryFlags {
  code: string;
  nameVi: string;
  nameEn?: string | null;
  sortOrder: number;
  active: boolean;
}

/** Trimmed text, or null for an empty value (an optional backend field is cleared, not sent as ""). */
const textOrNull = (v: string | null | undefined): string | null => v?.trim() || null;

function EmployeeCategoryForm({ category, onClose }: { category: EmployeeCategory | null; onClose: () => void }) {
  const { t } = useTranslation();
  const { t: te } = useTranslation(EMPLOYEE_CATEGORIES_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateEmployeeCategory();
  const update = useUpdateEmployeeCategory();
  const mutation = category ? update : create;

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const flags: EmployeeCategoryFlags = {
      probation: !!v.probation,
      seasonal: !!v.seasonal,
      intern: !!v.intern,
      expat: !!v.expat,
      collaborator: !!v.collaborator,
      countsInHeadcount: !!v.countsInHeadcount,
    };
    const nameVi = v.nameVi.trim();
    const nameEn = textOrNull(v.nameEn);
    try {
      if (category) {
        await update.mutateAsync({
          id: category.id,
          body: { nameVi, nameEn, flags, sortOrder: v.sortOrder, active: v.active, version: category.version },
        });
        fb.saved();
      } else {
        await create.mutateAsync({ code: v.code.trim(), nameVi, nameEn, flags, sortOrder: v.sortOrder });
        fb.created();
      }
      onClose();
    } catch (error) {
      // field-level messages land on the form; anything else is shown by the drawer's problem alert
      if (isProblem(error, 'validation-failed') && error.problem.errors?.length) {
        form.setFields(error.problem.errors.map((e) => ({ name: e.field.split('.'), errors: [e.message] })));
      }
    }
  };

  const initial: Partial<FormValues> = category
    ? { code: category.code, nameVi: category.nameVi, nameEn: category.nameEn, sortOrder: category.sortOrder,
        active: category.active, ...category.flags }
    : { sortOrder: 0, active: true, ...NO_FLAGS };

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={category ? te('page.edit') : te('page.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={520}
    >
      <Form<FormValues> form={form} layout="vertical" initialValues={initial}>
        <Form.Item
          name="code"
          label={t('field.code')}
          extra={category ? te('page.codeImmutable') : undefined}
          // codes are upper case by contract; typing lower case is corrected instead of rejected
          getValueFromEvent={(e: { target: { value: string } }) => e.target.value.toUpperCase()}
          rules={[
            { required: true, whitespace: true, message: te('page.codeRequired') },
            { pattern: CATEGORY_CODE_PATTERN, message: te('page.codeInvalid') },
          ]}
        >
          <Input disabled={!!category} autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item
          name="nameVi"
          label={t('field.nameVi')}
          rules={[{ required: true, whitespace: true, message: te('page.nameRequired') }, { max: 200 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item name="nameEn" label={t('field.nameEn')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="sortOrder" label={te('page.sortOrder')}>
          <InputNumber min={0} max={9999} style={{ width: 120 }} />
        </Form.Item>
        <Form.Item label={te('page.flags')} extra={te('page.flagsHint')}>
          <Space direction="vertical" size={4}>
            {CATEGORY_KINDS.map((kind: CategoryKind) => (
              <Form.Item key={kind} name={kind} valuePropName="checked" noStyle>
                <Checkbox>{te(`flag.${kind}`)}</Checkbox>
              </Form.Item>
            ))}
            <Form.Item name="countsInHeadcount" valuePropName="checked" noStyle>
              <Checkbox>{te('flag.countsInHeadcount')}</Checkbox>
            </Form.Item>
          </Space>
        </Form.Item>
        {category ? (
          <Form.Item name="active" label={te('page.active')} valuePropName="checked">
            <Switch />
          </Form.Item>
        ) : null}
      </Form>
    </FormDrawer>
  );
}
