import { useEffect, useState } from 'react';
import { Button, Checkbox, Form, Input, InputNumber, Tag } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { usePermission } from '@/shared/auth';
import { DataTable, FormDrawer, useFeedback } from '@/shared/components';
import { applyFieldErrors, blankToNull, CodeLabel, CodeValueSelect, type ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { CodeLists, type QualificationRequest, type QualificationView } from './api';
import { useAddQualification, useChangeQualification, useQualifications } from './queries';
import { ScanActions } from './shared';
import './i18n';

/** Education and vocational qualifications (US-PPL-010); exactly one is the highest (labour book). */
export function QualificationsTab({ personId, profile }: ProfileTabProps) {
  const { t } = useTranslation('people-person');
  const { t: tc } = useTranslation();
  const [editing, setEditing] = useState<QualificationView | 'new' | null>(null);
  const query = useQualifications(personId);
  const canUpdate = usePermission(P.PERSON_UPDATE) && !profile.person.anonymised;

  return (
    <div>
      {canUpdate ? (
        <div style={{ textAlign: 'right', marginBottom: 12 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>
            {t('qualifications.add')}
          </Button>
        </div>
      ) : null}
      <DataTable<QualificationView>
        size="small"
        data={query.data}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: t('qualifications.level'),
            dataIndex: 'qualificationLevelCode',
            render: (c: string, q) => (
              <>
                <CodeLabel list={CodeLists.QUALIFICATION_LEVEL} code={c} />{' '}
                {q.highest ? <Tag color="blue">{t('qualifications.highest')}</Tag> : null}
              </>
            ),
          },
          {
            title: t('qualifications.vocational'),
            dataIndex: 'vocationalSkillLevelCode',
            render: (c: string | null) => <CodeLabel list={CodeLists.VOCATIONAL_SKILL_LEVEL} code={c} />,
          },
          {
            title: t('qualifications.generalEducation'),
            dataIndex: 'generalEducationCode',
            render: (c: string | null) => <CodeLabel list={CodeLists.GENERAL_EDUCATION} code={c} />,
          },
          { title: t('qualifications.fieldOfStudy'), dataIndex: 'fieldOfStudy' },
          { title: t('qualifications.institution'), dataIndex: 'institutionName' },
          { title: t('qualifications.graduationYear'), dataIndex: 'graduationYear' },
          {
            title: t('documents.scan'),
            key: 'scan',
            render: (_, q) => (
              <ScanActions personId={personId} recordId={q.id} scanDocumentId={q.scanDocumentId} target="QUALIFICATION" canUpload={canUpdate} />
            ),
          },
          {
            title: tc('field.actions'),
            key: 'actions',
            render: (_, q) =>
              canUpdate ? (
                <Button type="link" size="small" onClick={() => setEditing(q)}>
                  {tc('action.edit')}
                </Button>
              ) : null,
          },
        ]}
      />
      <QualificationDrawer personId={personId} editing={editing} onClose={() => setEditing(null)} isFirst={(query.data ?? []).length === 0} />
    </div>
  );
}

interface QualificationValues {
  qualificationLevelCode: string;
  vocationalSkillLevelCode?: string;
  generalEducationCode?: string;
  fieldOfStudy?: string;
  institutionName?: string;
  graduationYear?: number | null;
  highest?: boolean;
}

function QualificationDrawer({
  personId,
  editing,
  onClose,
  isFirst,
}: {
  personId: UUID;
  editing: QualificationView | 'new' | null;
  onClose: () => void;
  isFirst: boolean;
}) {
  const { t } = useTranslation('people-person');
  const fb = useFeedback();
  const [form] = Form.useForm<QualificationValues>();
  const add = useAddQualification(personId);
  const change = useChangeQualification(personId);
  const existing = editing && editing !== 'new' ? editing : null;
  const mutation = existing ? change : add;

  useEffect(() => {
    if (!editing) return;
    form.resetFields();
    add.reset();
    change.reset();
    if (existing) {
      form.setFieldsValue({
        qualificationLevelCode: existing.qualificationLevelCode,
        vocationalSkillLevelCode: existing.vocationalSkillLevelCode ?? undefined,
        generalEducationCode: existing.generalEducationCode ?? undefined,
        fieldOfStudy: existing.fieldOfStudy ?? undefined,
        institutionName: existing.institutionName ?? undefined,
        graduationYear: existing.graduationYear ?? null,
        highest: existing.highest,
      });
    } else {
      form.setFieldsValue({ highest: isFirst });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [editing, form]);

  const submit = async () => {
    let v: QualificationValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const qualification = { ...blankToNull(v as unknown as Record<string, unknown>), highest: !!v.highest, graduationYear: v.graduationYear ?? null } as QualificationRequest;
    try {
      if (existing) await change.mutateAsync({ qualificationId: existing.id, version: existing.version, qualification });
      else await add.mutateAsync(qualification);
      fb.saved(t('qualifications.saved'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer
      open={editing !== null}
      onClose={onClose}
      title={existing ? t('qualifications.edit') : t('qualifications.add')}
      onSubmit={() => void submit()}
      submitting={mutation.isPending}
      error={mutation.error}
      width={520}
    >
      <Form<QualificationValues> form={form} layout="vertical">
        <Form.Item name="qualificationLevelCode" label={t('qualifications.level')} rules={[{ required: true, message: t('qualifications.levelRequired') }]}>
          <CodeValueSelect list={CodeLists.QUALIFICATION_LEVEL} />
        </Form.Item>
        <Form.Item name="vocationalSkillLevelCode" label={t('qualifications.vocational')}>
          <CodeValueSelect list={CodeLists.VOCATIONAL_SKILL_LEVEL} />
        </Form.Item>
        <Form.Item name="generalEducationCode" label={t('qualifications.generalEducation')}>
          <CodeValueSelect list={CodeLists.GENERAL_EDUCATION} />
        </Form.Item>
        <Form.Item name="fieldOfStudy" label={t('qualifications.fieldOfStudy')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="institutionName" label={t('qualifications.institution')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="graduationYear" label={t('qualifications.graduationYear')}>
          <InputNumber min={1900} max={2100} precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="highest" valuePropName="checked" extra={t('qualifications.highestHint')}>
          <Checkbox>{t('qualifications.highest')}</Checkbox>
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
