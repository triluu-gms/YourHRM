import { useMemo, useState } from 'react';
import { Button, Checkbox, DatePicker, Form, Input, Select, Space, Typography } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import { isProblem, type UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { AsOfPicker, DataTable, FormDrawer, PageHeader, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatValidity, ISO_DATE, today } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { useLegalEntities } from '../employer/queries';
import { LegalEntitySelect, legalEntityLabel } from '../employer/widgets';
import { userSearchOptions } from '../access/queries';
import type { UserView } from '../access/api';
import { COST_CENTRE_CODE_PATTERN, type CostCentre } from './api';
import { useCostCentres, useCreateCostCentre, useUpdateCostCentre } from './queries';
import { COST_CENTRES_NS } from './i18n';

const userLabel = (u: UserView) => (u.displayName ? `${u.displayName} (${u.login})` : u.login);

/**
 * Cost centres (BR-CORE-025, US-CORE-022): one list per legal entity, effective-dated. Create, rename, change the
 * GL segment / owner, or end one — ending is refused by the backend while an org unit still defaults to it.
 */
export function CostCentresPage() {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(COST_CENTRES_NS);
  const [allDates, setAllDates] = useState(false);
  const [asOf, setAsOf] = useState(today);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [form, setForm] = useState<{ costCentre: CostCentre | null } | null>(null);
  const canUpdate = usePermission(P.COST_CENTRE_UPDATE);
  const query = useCostCentres({ legalEntityId, ...(allDates ? { allDates: true } : { asOf }) });
  const legalEntities = useLegalEntities();
  const entityLabel = useMemo(
    () => new Map((legalEntities.data ?? []).map((le) => [le.id, legalEntityLabel(le)])),
    [legalEntities.data],
  );
  const owners = useOwnerNames();

  return (
    <div>
      <PageHeader title={tc('page.title')} subTitle={tc('page.subtitle')} />

      <Space wrap style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <Space wrap>
          <LegalEntitySelect allowClear style={{ width: 260 }} value={legalEntityId} onChange={setLegalEntityId} />
          <Checkbox checked={allDates} onChange={(e) => setAllDates(e.target.checked)}>
            {tc('page.allPeriods')}
          </Checkbox>
          {!allDates ? <AsOfPicker value={asOf} onChange={setAsOf} /> : null}
        </Space>
        <PermissionGate anyOf={P.COST_CENTRE_CREATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setForm({ costCentre: null })}>
            {tc('page.create')}
          </Button>
        </PermissionGate>
      </Space>

      <DataTable<CostCentre>
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        locale={{ emptyText: tc('page.empty') }}
        columns={[
          { title: t('field.code'), dataIndex: 'code', render: (c: string) => <code>{c}</code> },
          { title: t('field.name'), dataIndex: 'name' },
          {
            title: tc('page.legalEntity'),
            key: 'legalEntity',
            render: (_: unknown, row: CostCentre) => entityLabel.get(row.legalEntityId) ?? row.legalEntityId.slice(0, 8),
          },
          { title: tc('page.glSegment'), dataIndex: 'glSegment' },
          {
            title: tc('page.owner'),
            key: 'owner',
            render: (_: unknown, row: CostCentre) =>
              row.ownerUserId ? (owners.get(row.ownerUserId) ?? row.ownerUserId.slice(0, 8)) : null,
          },
          {
            title: tc('page.validity'),
            key: 'validity',
            render: (_: unknown, row: CostCentre) => formatValidity(row.validFrom, row.validTo, t('common.openEnded')),
          },
          ...(canUpdate
            ? [
                {
                  title: t('field.actions'),
                  key: 'actions',
                  render: (_: unknown, row: CostCentre) => (
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      aria-label={`${t('action.edit')} ${row.code}`}
                      onClick={() => setForm({ costCentre: row })}
                    />
                  ),
                },
              ]
            : []),
        ]}
      />

      {form ? <CostCentreForm costCentre={form.costCentre} onClose={() => setForm(null)} /> : null}
    </div>
  );
}

/** Owner id → display name, from one user search; empty when the caller may not read the user list. */
function useOwnerNames(): Map<UUID, string> {
  const canViewUsers = usePermission(P.USER_VIEW);
  const users = useQuery({
    ...userSearchOptions({ status: 'ACTIVE', page: 0, size: 200 }),
    enabled: canViewUsers,
  });
  return useMemo(() => new Map((users.data?.items ?? []).map((u) => [u.id, userLabel(u)])), [users.data]);
}

interface FormValues {
  legalEntityId: UUID;
  code: string;
  name: string;
  glSegment?: string | null;
  ownerUserId?: UUID | null;
  validFrom?: Dayjs | null;
  validTo?: Dayjs | null;
}

/** Trimmed text, or null for an empty value (an optional backend field is cleared, not sent as ""). */
const textOrNull = (v: string | null | undefined): string | null => v?.trim() || null;

function CostCentreForm({ costCentre, onClose }: { costCentre: CostCentre | null; onClose: () => void }) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation(COST_CENTRES_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const create = useCreateCostCentre();
  const update = useUpdateCostCentre();
  const mutation = costCentre ? update : create;

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const name = v.name.trim();
    const glSegment = textOrNull(v.glSegment);
    const ownerUserId = v.ownerUserId ?? null;
    const validTo = v.validTo ? v.validTo.format(ISO_DATE) : null;
    try {
      if (costCentre) {
        await update.mutateAsync({
          id: costCentre.id,
          body: { name, glSegment, ownerUserId, validTo, version: costCentre.version },
        });
        fb.saved();
      } else {
        await create.mutateAsync({
          legalEntityId: v.legalEntityId,
          code: v.code.trim(),
          name,
          glSegment,
          ownerUserId,
          validFrom: v.validFrom ? v.validFrom.format(ISO_DATE) : null,
          validTo,
        });
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

  return (
    <FormDrawer
      open
      onClose={onClose}
      title={costCentre ? tc('page.edit', { code: costCentre.code }) : tc('page.create')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={520}
    >
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={
          costCentre
            ? {
                legalEntityId: costCentre.legalEntityId,
                code: costCentre.code,
                name: costCentre.name,
                glSegment: costCentre.glSegment,
                ownerUserId: costCentre.ownerUserId,
                validFrom: dayjs(costCentre.validFrom),
                validTo: costCentre.validTo ? dayjs(costCentre.validTo) : null,
              }
            : {}
        }
      >
        <Form.Item
          name="legalEntityId"
          label={tc('page.legalEntity')}
          extra={costCentre ? tc('page.legalEntityImmutable') : undefined}
          rules={[{ required: true, message: tc('page.legalEntityRequired') }]}
        >
          <LegalEntitySelect disabled={!!costCentre} />
        </Form.Item>
        <Form.Item
          name="code"
          label={t('field.code')}
          extra={costCentre ? tc('page.codeImmutable') : undefined}
          rules={[
            { required: true, whitespace: true, message: tc('page.codeRequired') },
            { pattern: COST_CENTRE_CODE_PATTERN, message: tc('page.codeInvalid') },
          ]}
        >
          <Input disabled={!!costCentre} autoComplete="off" style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item
          name="name"
          label={t('field.name')}
          rules={[{ required: true, whitespace: true, message: tc('page.nameRequired') }, { max: 200 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item name="glSegment" label={tc('page.glSegment')} extra={tc('page.glSegmentHint')} rules={[{ max: 64 }]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="ownerUserId" label={tc('page.owner')} extra={tc('page.ownerHint')}>
          <OwnerSelect />
        </Form.Item>
        <Form.Item name="validFrom" label={t('field.validFrom')} extra={costCentre ? undefined : tc('page.validFromHint')}>
          <DatePicker format={DATE_FORMAT} disabled={!!costCentre} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="validTo"
          label={t('field.validTo')}
          extra={tc('page.validToHint')}
          dependencies={['validFrom']}
          rules={[
            ({ getFieldValue }) => ({
              validator: (_, value: Dayjs | null | undefined) => {
                const from: Dayjs | null | undefined = getFieldValue('validFrom');
                const start = from ?? dayjs().startOf('day');
                return value && !value.isAfter(start, 'day')
                  ? Promise.reject(new Error(tc('page.validToAfterFrom')))
                  : Promise.resolve();
              },
            }),
          ]}
        >
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

/**
 * Owner picker over the active users. Without `core.user.view` the list cannot be read, so the current owner is
 * shown read-only and kept as it is — the form still submits the id it loaded.
 */
function OwnerSelect({
  value,
  onChange,
  id,
}: {
  value?: UUID | null;
  onChange?: (value: UUID | null) => void;
  id?: string;
}) {
  const { t: tc } = useTranslation(COST_CENTRES_NS);
  const canViewUsers = usePermission(P.USER_VIEW);
  const [q, setQ] = useState('');
  const users = useQuery({
    ...userSearchOptions({ q: q.trim() || undefined, status: 'ACTIVE', page: 0, size: 20 }),
    enabled: canViewUsers,
  });

  if (!canViewUsers) {
    return <Typography.Text type="secondary">{value ? value : tc('page.ownerUnavailable')}</Typography.Text>;
  }

  const options = (users.data?.items ?? []).map((u) => ({ value: u.id, label: userLabel(u) }));
  if (value && !options.some((o) => o.value === value)) options.unshift({ value, label: value });
  return (
    <Select
      id={id}
      showSearch
      allowClear
      filterOption={false}
      value={value ?? undefined}
      placeholder={tc('page.ownerSearch')}
      onSearch={setQ}
      onChange={(v) => onChange?.(v ?? null)}
      loading={users.isFetching}
      notFoundContent={tc('page.ownerNoUsers')}
      options={options}
    />
  );
}
