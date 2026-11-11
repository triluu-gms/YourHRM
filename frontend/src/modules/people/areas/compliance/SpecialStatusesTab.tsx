import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, DatePicker, Form, Input, Select, Space, Tag, Typography } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, FormDrawer, StatusTag, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { applyFieldErrors, CodeLabel, CodeValueSelect, fromIsoDate, toIsoDate, type EmployeeProfile, type ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { CONSENT_STATUS_TYPES, DERIVED_STATUS_TYPES, SPECIAL_STATUS_TYPES, type StatusView } from './api';
import { EvidenceCell } from './EvidenceCell';
import { useAddSpecialStatus, useCancelSpecialStatus, useChangeSpecialStatus, useSpecialStatuses } from './queries';
import './i18n';

const HAZARD_LIST = 'JOB_HAZARD_CATEGORY';
const PROTECTIVE = ['PREGNANCY', 'CHILD_UNDER_12M'];

/** Special statuses (US-PPL-012): pregnancy / child under 12 months (protective), disability, hazardous-job override… */
export function SpecialStatusesTab({ personId, profile }: ProfileTabProps) {
  const { t } = useTranslation();
  const { t: tc } = useTranslation('people-compliance');
  const fb = useFeedback();
  const [includeCancelled, setIncludeCancelled] = useState(false);
  const query = useSpecialStatuses(personId, includeCancelled);
  const cancel = useCancelSpecialStatus(personId);
  const [editing, setEditing] = useState<StatusView | null | undefined>(undefined);

  const onCancel = async (s: StatusView) => {
    try {
      await cancel.mutateAsync({ id: s.id, version: s.version });
      fb.done(tc('statuses.cancelled'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <div>
      <Space style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }} wrap>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {tc('statuses.title')}
        </Typography.Title>
        <Space wrap>
          <Checkbox checked={includeCancelled} onChange={(e) => setIncludeCancelled(e.target.checked)}>
            {tc('statuses.includeCancelled')}
          </Checkbox>
          <PermissionGate anyOf={P.SPECIAL_STATUS_UPDATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing(null)}>
              {tc('statuses.add')}
            </Button>
          </PermissionGate>
        </Space>
      </Space>
      <Alert type="info" showIcon message={tc('statuses.intro')} style={{ marginBottom: 12 }} />
      <DataTable<StatusView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: tc('statuses.type'),
            dataIndex: 'statusType',
            render: (v: string, s) => (
              <Space size={4} wrap>
                {tc(`statuses.types.${v}`, { defaultValue: v })}
                {s.derived ? <Tag>{tc('statuses.derived')}</Tag> : null}
                {s.sensitive ? <Tag color="orange">{tc('statuses.sensitive')}</Tag> : null}
                {PROTECTIVE.includes(v) ? <Tag color="green">{tc('statuses.protective')}</Tag> : null}
              </Space>
            ),
          },
          { title: tc('common.validFrom'), dataIndex: 'validFrom', render: (v: string) => formatDate(v) },
          { title: tc('common.validTo'), dataIndex: 'validTo', render: (v: string | null) => formatDate(v) || '—' },
          {
            title: tc('statuses.classification'),
            dataIndex: 'classificationCode',
            render: (c: string | null) => (c ? <CodeLabel list={HAZARD_LIST} code={c} /> : '—'),
          },
          { title: t('field.status'), dataIndex: 'status', render: (s: string) => <StatusTag status={s} /> },
          {
            title: tc('common.evidence'),
            key: 'evidence',
            render: (_, s) =>
              s.derived ? null : (
                <EvidenceCell personId={personId} kind="specialStatus" rowId={s.id} documentId={s.evidenceDocumentId} permission={P.SPECIAL_STATUS_UPDATE} />
              ),
          },
          {
            title: t('field.actions'),
            key: 'actions',
            render: (_, s) =>
              s.derived || s.status === 'CANCELLED' ? null : (
                <PermissionGate anyOf={P.SPECIAL_STATUS_UPDATE}>
                  <Space size={0}>
                    <Button type="link" size="small" icon={<EditOutlined />} onClick={() => setEditing(s)}>
                      {t('action.edit')}
                    </Button>
                    <ConfirmButton type="link" size="small" danger title={tc('statuses.cancelConfirm')} onConfirm={() => onCancel(s)}>
                      {tc('statuses.cancel')}
                    </ConfirmButton>
                  </Space>
                </PermissionGate>
              ),
          },
        ]}
      />
      <StatusDrawer personId={personId} profile={profile} status={editing} onClose={() => setEditing(undefined)} />
    </div>
  );
}

interface StatusValues {
  statusType: string;
  employmentId?: UUID;
  validFrom: Dayjs;
  validTo?: Dayjs | null;
  classificationCode?: string;
  overrideReason?: string;
  note?: string;
}

function StatusDrawer({
  personId,
  profile,
  status,
  onClose,
}: {
  personId: UUID;
  profile: EmployeeProfile;
  status: StatusView | null | undefined;
  onClose: () => void;
}) {
  const { t: tc } = useTranslation('people-compliance');
  const fb = useFeedback();
  const [form] = Form.useForm<StatusValues>();
  const add = useAddSpecialStatus(personId);
  const change = useChangeSpecialStatus(personId);
  const mutation = status ? change : add;
  const open = status !== undefined;
  const type = Form.useWatch('statusType', form) ?? status?.statusType;
  const hazardous = type === 'HAZARDOUS_JOB';

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    add.reset();
    change.reset();
    if (status) {
      form.setFieldsValue({
        statusType: status.statusType,
        employmentId: status.employmentId ?? undefined,
        validFrom: fromIsoDate(status.validFrom) as Dayjs,
        validTo: fromIsoDate(status.validTo),
        classificationCode: status.classificationCode ?? undefined,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, status, form]);

  const onSubmit = async () => {
    let v: StatusValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const details = v.note?.trim() ? { note: v.note.trim() } : null;
    try {
      if (status) {
        await change.mutateAsync({
          id: status.id,
          body: {
            version: status.version,
            validTo: toIsoDate(v.validTo),
            classificationCode: v.classificationCode ?? null,
            details,
            overrideReason: v.overrideReason?.trim() || null,
          },
        });
      } else {
        await add.mutateAsync({
          statusType: v.statusType,
          employmentId: hazardous ? (v.employmentId ?? null) : null,
          validFrom: toIsoDate(v.validFrom) as string,
          validTo: toIsoDate(v.validTo),
          classificationCode: hazardous ? (v.classificationCode ?? null) : null,
          details,
          overrideReason: v.overrideReason?.trim() || null,
        });
      }
      fb.saved();
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const employments = [profile.employment, ...profile.otherEmployments].map((e) => ({ value: e.employmentId, label: e.employeeNo }));

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={status ? tc('statuses.edit') : tc('statuses.add')}
      onSubmit={() => void onSubmit()}
      submitting={mutation.isPending}
      error={mutation.error}
    >
      <Form<StatusValues> form={form} layout="vertical">
        <Form.Item name="statusType" label={tc('statuses.type')} rules={[{ required: true, message: tc('statuses.typeRequired') }]}>
          <Select
            disabled={!!status}
            options={SPECIAL_STATUS_TYPES.filter((s) => !DERIVED_STATUS_TYPES.includes(s)).map((s) => ({ value: s, label: tc(`statuses.types.${s}`) }))}
          />
        </Form.Item>
        {CONSENT_STATUS_TYPES.includes(type ?? '') ? <Alert type="warning" showIcon message={tc('statuses.consentHint')} style={{ marginBottom: 16 }} /> : null}
        {hazardous ? (
          <>
            <Form.Item name="employmentId" label={tc('statuses.employment')} rules={[{ required: true, message: tc('statuses.employmentRequired') }]}>
              <Select disabled={!!status} options={employments} />
            </Form.Item>
            <Form.Item name="classificationCode" label={tc('statuses.classification')}>
              <CodeValueSelect list={HAZARD_LIST} />
            </Form.Item>
            <Form.Item
              name="overrideReason"
              label={tc('statuses.overrideReason')}
              rules={[{ required: true, whitespace: true, message: tc('statuses.overrideRequired') }, { max: 500 }]}
            >
              <Input.TextArea rows={2} />
            </Form.Item>
          </>
        ) : null}
        <Space wrap>
          <Form.Item name="validFrom" label={tc('common.validFrom')} rules={[{ required: true, message: tc('residency.validFromRequired') }]}>
            <DatePicker format={DATE_FORMAT} disabled={!!status} />
          </Form.Item>
          <Form.Item name="validTo" label={tc('common.validTo')}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
        </Space>
        <Form.Item name="note" label={tc('statuses.note')} rules={[{ max: 500 }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
