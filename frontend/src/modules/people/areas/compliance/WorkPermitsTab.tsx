import { useEffect, useState } from 'react';
import { Button, DatePicker, Form, Input, Select, Space, Typography } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { DataTable, FormDrawer, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { applyFieldErrors, blankToNull, fromIsoDate, toIsoDate, type EmployeeProfile, type ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { PERMIT_STATUSES, PERMIT_TRANSITIONS, PERMIT_TYPES, type PermitView } from './api';
import { EvidenceCell } from './EvidenceCell';
import { useSaveWorkPermit, useWorkPermits } from './queries';
import './i18n';

/** Work permits / exemptions of a foreign employee (US-PPL-011): status lifecycle, renewal, scan. */
export function WorkPermitsTab({ personId, profile }: ProfileTabProps) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation('people-compliance');
  const query = useWorkPermits(personId);
  const [editing, setEditing] = useState<PermitView | null | undefined>(undefined);
  const statusLabels = Object.fromEntries(PERMIT_STATUSES.map((s) => [s, tc(`permits.statuses.${s}`)]));

  return (
    <div>
      <Space style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {tc('permits.title')}
        </Typography.Title>
        <PermissionGate anyOf={P.WORK_PERMIT_UPDATE}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing(null)}>
            {tc('permits.add')}
          </Button>
        </PermissionGate>
      </Space>
      <DataTable<PermitView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          { title: tc('permits.type'), dataIndex: 'permitType', render: (v: string) => tc(`permits.types.${v}`, { defaultValue: v }) },
          { title: tc('permits.permitNo'), dataIndex: 'permitNo', render: (v: string | null) => v ?? '—' },
          { title: tc('permits.jobTitleOnPermit'), dataIndex: 'jobTitleOnPermit', render: (v: string | null) => v ?? '—' },
          { title: tc('common.validFrom'), dataIndex: 'validFrom', render: (v: string) => formatDate(v) },
          { title: tc('common.validTo'), dataIndex: 'validTo', render: (v: string | null) => formatDate(v) || '—' },
          { title: tc('permits.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} labels={statusLabels} /> },
          {
            title: tc('permits.scan'),
            key: 'scan',
            render: (_, p) => <EvidenceCell personId={personId} kind="workPermit" rowId={p.id} documentId={p.scanDocumentId} permission={P.WORK_PERMIT_UPDATE} />,
          },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, p) => (
              <PermissionGate anyOf={P.WORK_PERMIT_UPDATE}>
                <Button type="link" size="small" icon={<EditOutlined />} onClick={() => setEditing(p)}>
                  {t('action.edit')}
                </Button>
              </PermissionGate>
            ),
          },
        ]}
      />
      <PermitDrawer personId={personId} profile={profile} permits={query.data ?? []} permit={editing} onClose={() => setEditing(undefined)} />
    </div>
  );
}

interface PermitValues {
  employmentId?: UUID;
  permitType: string;
  permitNo?: string;
  jobTitleOnPermit?: string;
  workLocationText?: string;
  issuingAuthority?: string;
  issueDate?: Dayjs | null;
  validFrom: Dayjs;
  validTo?: Dayjs | null;
  renewalOfId?: UUID;
  status?: string;
}

function PermitDrawer({
  personId,
  profile,
  permits,
  permit,
  onClose,
}: {
  personId: UUID;
  profile: EmployeeProfile;
  permits: PermitView[];
  permit: PermitView | null | undefined;
  onClose: () => void;
}) {
  const { t: tc } = useTranslation('people-compliance');
  const fb = useFeedback();
  const [form] = Form.useForm<PermitValues>();
  const save = useSaveWorkPermit(personId);
  const open = permit !== undefined;

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    save.reset();
    if (permit) {
      form.setFieldsValue({
        employmentId: permit.employmentId ?? undefined,
        permitType: permit.permitType,
        permitNo: permit.permitNo ?? undefined,
        jobTitleOnPermit: permit.jobTitleOnPermit ?? undefined,
        workLocationText: permit.workLocationText ?? undefined,
        issuingAuthority: permit.issuingAuthority ?? undefined,
        issueDate: fromIsoDate(permit.issueDate),
        validFrom: fromIsoDate(permit.validFrom) as Dayjs,
        validTo: fromIsoDate(permit.validTo),
        renewalOfId: permit.renewalOfId ?? undefined,
        status: permit.status,
      });
    } else {
      form.setFieldsValue({ employmentId: profile.employment.employmentId, status: 'DRAFT' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, permit, form]);

  const onSubmit = async () => {
    let v: PermitValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const text = blankToNull({
      permitNo: v.permitNo ?? '',
      jobTitleOnPermit: v.jobTitleOnPermit ?? '',
      workLocationText: v.workLocationText ?? '',
      issuingAuthority: v.issuingAuthority ?? '',
    });
    try {
      await save.mutateAsync({
        id: permit?.id,
        version: permit?.version,
        body: {
          ...text,
          employmentId: v.employmentId ?? null,
          permitType: v.permitType,
          issueDate: toIsoDate(v.issueDate),
          validFrom: toIsoDate(v.validFrom) as string,
          validTo: toIsoDate(v.validTo),
          renewalOfId: v.renewalOfId ?? null,
          status: v.status ?? null,
        },
      });
      fb.saved();
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const statuses = permit ? (PERMIT_TRANSITIONS[permit.status] ?? [permit.status]) : ['DRAFT', 'APPLIED', 'ACTIVE'];
  const employments = [profile.employment, ...profile.otherEmployments].map((e) => ({ value: e.employmentId, label: e.employeeNo }));

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={permit ? tc('permits.edit') : tc('permits.add')}
      onSubmit={() => void onSubmit()}
      submitting={save.isPending}
      error={save.error}
    >
      <Form<PermitValues> form={form} layout="vertical">
        <Form.Item name="permitType" label={tc('permits.type')} rules={[{ required: true, message: tc('permits.typeRequired') }]}>
          <Select options={PERMIT_TYPES.map((p) => ({ value: p, label: tc(`permits.types.${p}`) }))} />
        </Form.Item>
        <Form.Item name="status" label={tc('permits.status')}>
          <Select options={statuses.map((s) => ({ value: s, label: tc(`permits.statuses.${s}`) }))} />
        </Form.Item>
        <Form.Item name="employmentId" label={tc('permits.employment')}>
          <Select allowClear options={employments} />
        </Form.Item>
        <Form.Item name="permitNo" label={tc('permits.permitNo')} rules={[{ max: 64 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="jobTitleOnPermit" label={tc('permits.jobTitleOnPermit')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="workLocationText" label={tc('permits.workLocation')} rules={[{ max: 500 }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
        <Form.Item name="issuingAuthority" label={tc('permits.issuingAuthority')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Space wrap>
          <Form.Item name="issueDate" label={tc('permits.issueDate')}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="validFrom" label={tc('common.validFrom')} rules={[{ required: true, message: tc('permits.validFromRequired') }]}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
          <Form.Item name="validTo" label={tc('common.validTo')}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
        </Space>
        <Form.Item name="renewalOfId" label={tc('permits.renewalOf')}>
          <Select
            allowClear
            options={permits
              .filter((p) => p.id !== permit?.id)
              .map((p) => ({ value: p.id, label: `${p.permitNo ?? tc(`permits.types.${p.permitType}`)} (${formatDate(p.validFrom)})` }))}
          />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
