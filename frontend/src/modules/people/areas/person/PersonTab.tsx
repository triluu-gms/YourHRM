import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, DatePicker, Descriptions, Drawer, Form, Input, List, Select, Skeleton, Space, Tag, Typography } from 'antd';
import { EditOutlined, EyeOutlined, IdcardOutlined, MergeCellsOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { ConfirmButton, DescriptionList, FormDrawer, ProblemAlert, useFeedback } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import {
  applyFieldErrors,
  blankToNull,
  CodeLabel,
  CodeValueSelect,
  CountryName,
  CountrySelect,
  EmployeeSelect,
  fromIsoDate,
  MaskedValue,
  toIsoDate,
  type PersonView,
  type ProfileTabProps,
} from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import {
  CLEARABLE_FIELDS,
  CodeLists,
  NATIONAL_ID_TYPES,
  PERSON_FIELDS,
  SI_BOOK_CUSTODY,
  type IdentifiersRequest,
  type NationalIdType,
  type PersonField,
  type Unmasked,
} from './api';
import { useChangeIdentifiers, useMergePerson, useMergePreview, usePerson, useUnmaskPerson, useUpdatePerson } from './queries';
import { nationalIdRule, normaliseId, PATTERNS, PurposeDrawer } from './shared';
import './i18n';

type DrawerKind = 'edit' | 'identifiers' | 'unmask' | 'merge' | null;

/** Personal details of the employee's person (US-PPL-002/008/009/085, merge US-PPL-004). */
export function PersonTab({ personId, profile }: ProfileTabProps) {
  const { t } = useTranslation('people-person');
  const query = usePerson(personId);
  const [drawer, setDrawer] = useState<DrawerKind>(null);
  const [unmasked, setUnmasked] = useState<Unmasked | null>(null);
  const person = query.data ?? profile.person;
  const readOnly = person.anonymised;

  if (query.isLoading) return <Skeleton active />;

  return (
    <div>
      {query.error ? <ProblemAlert error={query.error} style={{ marginBottom: 12 }} /> : null}
      <Space wrap style={{ marginBottom: 12 }}>
        {!readOnly ? (
          <PermissionGate anyOf={P.PERSON_UPDATE}>
            <Button icon={<EditOutlined />} onClick={() => setDrawer('edit')}>
              {t('person.edit')}
            </Button>
            <Button icon={<IdcardOutlined />} onClick={() => setDrawer('identifiers')}>
              {t('person.identifiers')}
            </Button>
          </PermissionGate>
        ) : null}
        <PermissionGate anyOf={P.PERSON_VIEW_SENSITIVE}>
          <Button icon={<EyeOutlined />} onClick={() => setDrawer('unmask')}>
            {t('person.unmask')}
          </Button>
        </PermissionGate>
        {!readOnly ? (
          <PermissionGate anyOf={P.PERSON_MERGE}>
            <Button icon={<MergeCellsOutlined />} onClick={() => setDrawer('merge')}>
              {t('person.merge')}
            </Button>
          </PermissionGate>
        ) : null}
      </Space>

      {unmasked ? <UnmaskedValues unmasked={unmasked} onClose={() => setUnmasked(null)} /> : null}

      <PersonDetails person={person} />

      <PersonEditDrawer open={drawer === 'edit'} person={person} onClose={() => setDrawer(null)} />
      <IdentifiersDrawer open={drawer === 'identifiers'} person={person} onClose={() => setDrawer(null)} />
      <UnmaskPersonDrawer
        open={drawer === 'unmask'}
        personId={personId}
        onClose={() => setDrawer(null)}
        onShown={(u) => {
          setUnmasked(u);
          setDrawer(null);
        }}
      />
      <MergeDrawer open={drawer === 'merge'} person={person} onClose={() => setDrawer(null)} />
    </div>
  );
}

function PersonDetails({ person: p }: { person: PersonView }) {
  const { t } = useTranslation('people-person');
  const recorded = (v: boolean) => (v ? <Tag color="blue">{t('person.recorded')}</Tag> : <Tag>{t('person.notRecorded')}</Tag>);
  return (
    <DescriptionList
      items={[
        { label: t('person.fullName'), value: p.fullName },
        { label: t('person.latinName'), value: p.latinName, hideEmpty: true },
        { label: t('person.preferredName'), value: p.preferredName, hideEmpty: true },
        { label: t('person.birthDate'), value: formatDate(p.birthDate) },
        { label: t('person.gender'), value: <CodeLabel list={CodeLists.GENDER} code={p.genderCode} /> },
        { label: t('person.nationality'), value: <CountryName code={p.nationalityCode} nationality /> },
        {
          label: t('person.nationalIdType'),
          value: p.nationalIdType ? t(`person.idTypes.${p.nationalIdType}`, { defaultValue: p.nationalIdType }) : null,
        },
        { label: t('person.nationalId'), value: <MaskedValue value={p.nationalIdMasked} /> },
        { label: t('person.taxCode'), value: <MaskedValue value={p.taxCodeMasked} /> },
        { label: t('person.siNumber'), value: <MaskedValue value={p.siNumberMasked} /> },
        { label: t('person.hiCardNo'), value: <MaskedValue value={p.hiCardNoMasked} /> },
        { label: t('person.hiFacility'), value: p.hiFacilityCode, hideEmpty: true },
        {
          label: t('person.siBookCustody'),
          value: p.siBookCustody ? t(`person.custody.${p.siBookCustody}`, { defaultValue: p.siBookCustody }) : null,
        },
        { label: t('person.ethnicity'), value: recorded(p.ethnicityRecorded) },
        { label: t('person.religion'), value: recorded(p.religionRecorded) },
        { label: t('person.verificationStatus'), value: p.verificationStatus, hideEmpty: true },
        { label: t('person.source'), value: p.source, hideEmpty: true },
      ]}
    />
  );
}

function UnmaskedValues({ unmasked, onClose }: { unmasked: Unmasked; onClose: () => void }) {
  const { t } = useTranslation('people-person');
  const entries = Object.entries(unmasked.values);
  return (
    <Alert
      type="warning"
      showIcon
      closable
      onClose={onClose}
      style={{ marginBottom: 12 }}
      message={t('person.unmaskedValues')}
      description={
        <>
          {entries.length > 0 ? (
            <Descriptions size="small" column={1}>
              {entries.map(([field, value]) => (
                <Descriptions.Item key={field} label={t(`person.fields.${field}`, { defaultValue: field })}>
                  <Typography.Text code copyable>
                    {value}
                  </Typography.Text>
                </Descriptions.Item>
              ))}
            </Descriptions>
          ) : null}
          {unmasked.withheld.length > 0 ? (
            <Typography.Text type="secondary">
              {t('person.withheld', { fields: unmasked.withheld.map((f) => t(`person.fields.${f}`, { defaultValue: f })).join(', ') })}
            </Typography.Text>
          ) : null}
        </>
      }
    />
  );
}

// ------------------------------------------------------------------------------------------------- edit

interface EditValues {
  familyName: string;
  middleName?: string;
  givenName: string;
  latinName?: string;
  preferredName?: string;
  birthDate?: ReturnType<typeof fromIsoDate>;
  genderCode?: string;
  nationalityCode?: string;
  hiFacilityCode?: string;
  siBookCustody?: string;
}

/** `PersonView.birthDate` is masked to 1 January below the unmasked level; the PUT writes what the form holds. */
function looksMasked(date: string | null | undefined) {
  return !!date && date.endsWith('-01-01');
}

function PersonEditDrawer({ open, person, onClose }: { open: boolean; person: PersonView; onClose: () => void }) {
  const { t } = useTranslation('people-person');
  const fb = useFeedback();
  const [form] = Form.useForm<EditValues>();
  const update = useUpdatePerson(person.id);
  const unmask = useUnmaskPerson(person.id);
  const canUnmask = usePermission(P.PERSON_VIEW_SENSITIVE);
  const [birthLoaded, setBirthLoaded] = useState(false);
  const close = () => {
    setBirthLoaded(false);
    onClose();
  };

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    update.reset();
    form.setFieldsValue({
      familyName: person.familyName ?? '',
      middleName: person.middleName ?? undefined,
      givenName: person.givenName ?? '',
      latinName: person.latinName ?? undefined,
      preferredName: person.preferredName ?? undefined,
      birthDate: fromIsoDate(person.birthDate),
      genderCode: person.genderCode ?? undefined,
      nationalityCode: person.nationalityCode ?? undefined,
      hiFacilityCode: person.hiFacilityCode ?? undefined,
      siBookCustody: person.siBookCustody ?? undefined,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, person, form]);

  const loadBirthDate = async () => {
    try {
      const u = await unmask.mutateAsync({ fields: ['BIRTH_DATE'], purpose: t('person.edit') });
      const v = u.values.BIRTH_DATE;
      if (v) form.setFieldValue('birthDate', fromIsoDate(v));
      setBirthLoaded(true);
    } catch (e) {
      fb.failed(e);
    }
  };

  const submit = async () => {
    let values: EditValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const { birthDate, ...rest } = values;
    try {
      await update.mutateAsync({
        version: person.version,
        person: { ...blankToNull(rest), familyName: rest.familyName.trim(), givenName: rest.givenName.trim(), birthDate: toIsoDate(birthDate) },
      });
      fb.saved(t('person.updated'));
      close();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={close} title={t('person.edit')} onSubmit={() => void submit()} submitting={update.isPending} error={update.error}>
      <Form<EditValues> form={form} layout="vertical">
        <Space.Compact block>
          <Form.Item name="familyName" label={t('person.familyName')} style={{ flex: 1 }} rules={[{ required: true, whitespace: true, message: t('person.familyNameRequired') }, { max: 100 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="middleName" label={t('person.middleName')} style={{ flex: 1 }} rules={[{ max: 100 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="givenName" label={t('person.givenName')} style={{ flex: 1 }} rules={[{ required: true, whitespace: true, message: t('person.givenNameRequired') }, { max: 100 }]}>
            <Input />
          </Form.Item>
        </Space.Compact>
        <Form.Item name="latinName" label={t('person.latinName')} rules={[{ max: 200 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="preferredName" label={t('person.preferredName')} rules={[{ max: 100 }]}>
          <Input />
        </Form.Item>
        {looksMasked(person.birthDate) && !birthLoaded ? (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message={t('person.birthDateMasked')}
            action={
              canUnmask ? (
                <Button size="small" loading={unmask.isPending} onClick={() => void loadBirthDate()}>
                  {t('person.loadBirthDate')}
                </Button>
              ) : undefined
            }
          />
        ) : null}
        <Form.Item name="birthDate" label={t('person.birthDate')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="genderCode" label={t('person.gender')}>
          <CodeValueSelect list={CodeLists.GENDER} />
        </Form.Item>
        <Form.Item name="nationalityCode" label={t('person.nationality')}>
          <CountrySelect nationality />
        </Form.Item>
        <Form.Item name="hiFacilityCode" label={t('person.hiFacility')} rules={[{ max: 32 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="siBookCustody" label={t('person.siBookCustody')}>
          <Select allowClear options={SI_BOOK_CUSTODY.map((c) => ({ value: c, label: t(`person.custody.${c}`) }))} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------ identifiers

interface IdentifierValues extends Omit<IdentifiersRequest, 'legacyTaxCode'> {
  clear?: PersonField[];
}

function IdentifiersDrawer({ open, person, onClose }: { open: boolean; person: PersonView; onClose: () => void }) {
  const { t } = useTranslation('people-person');
  const fb = useFeedback();
  const [form] = Form.useForm<IdentifierValues>();
  const change = useChangeIdentifiers(person.id);
  const idType = Form.useWatch('nationalIdType', form) as NationalIdType | undefined;

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    change.reset();
    form.setFieldsValue({ nationalIdType: (person.nationalIdType as NationalIdType) ?? undefined, clear: [] });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, person, form]);

  const submit = async () => {
    let values: IdentifierValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const { clear, ...set } = values;
    const cleaned = blankToNull(set as Record<string, unknown>) as IdentifiersRequest;
    // nationalIdType alone is not a change unless a number is given or it differs from the stored type.
    if (!cleaned.nationalId && cleaned.nationalIdType === person.nationalIdType) cleaned.nationalIdType = null;
    try {
      await change.mutateAsync({ version: person.version, set: cleaned, clear: clear ?? [] });
      fb.saved(t('person.identifiersUpdated'));
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  return (
    <FormDrawer open={open} onClose={onClose} title={t('person.identifiers')} onSubmit={() => void submit()} submitting={change.isPending} error={change.error}>
      <Alert type="info" showIcon message={t('person.identifiersHint')} style={{ marginBottom: 12 }} />
      <Form<IdentifierValues> form={form} layout="vertical">
        <Form.Item name="nationalIdType" label={t('person.nationalIdType')}>
          <Select allowClear options={NATIONAL_ID_TYPES.map((v) => ({ value: v, label: t(`person.idTypes.${v}`) }))} />
        </Form.Item>
        <Form.Item name="nationalId" label={t('person.nationalId')} normalize={normaliseId} rules={[nationalIdRule(idType, t)]} extra={<MaskedValue value={person.nationalIdMasked} />}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="taxCode" label={t('person.taxCode')} normalize={normaliseId} rules={[{ pattern: PATTERNS.TAX_CODE, message: t('person.taxCodeInvalid') }]} extra={<MaskedValue value={person.taxCodeMasked} />}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="siNumber" label={t('person.siNumber')} normalize={normaliseId} rules={[{ pattern: PATTERNS.SI_NUMBER, message: t('person.siNumberInvalid') }]} extra={<MaskedValue value={person.siNumberMasked} />}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="hiCardNo" label={t('person.hiCardNo')} normalize={normaliseId} rules={[{ pattern: PATTERNS.HI_CARD, message: t('person.hiCardInvalid') }]} extra={<MaskedValue value={person.hiCardNoMasked} />}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="ethnicityCode" label={t('person.ethnicity')}>
          <CodeValueSelect list={CodeLists.ETHNICITY} />
        </Form.Item>
        <Form.Item name="religionCode" label={t('person.religion')}>
          <CodeValueSelect list={CodeLists.RELIGION} />
        </Form.Item>
        <Form.Item name="clear" label={t('person.clear')}>
          <Checkbox.Group options={CLEARABLE_FIELDS.map((f) => ({ value: f, label: t(`person.fields.${f}`) }))} />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ---------------------------------------------------------------------------------------------- unmask

function UnmaskPersonDrawer({
  open,
  personId,
  onClose,
  onShown,
}: {
  open: boolean;
  personId: UUID;
  onClose: () => void;
  onShown: (u: Unmasked) => void;
}) {
  const { t } = useTranslation('people-person');
  const unmask = useUnmaskPerson(personId);
  return (
    <PurposeDrawer<{ purpose: string; fields: PersonField[] }>
      open={open}
      title={t('person.unmaskTitle')}
      onClose={() => {
        unmask.reset();
        onClose();
      }}
      submitting={unmask.isPending}
      error={unmask.error}
      onSubmit={(v) => unmask.mutate({ fields: v.fields, purpose: v.purpose.trim() }, { onSuccess: onShown })}
    >
      <Form.Item
        name="fields"
        label={t('person.unmaskFields')}
        initialValue={['NATIONAL_ID']}
        rules={[{ required: true, type: 'array', min: 1, message: t('person.unmaskFieldsRequired') }]}
      >
        <Checkbox.Group options={PERSON_FIELDS.map((f) => ({ value: f, label: t(`person.fields.${f}`) }))} />
      </Form.Item>
    </PurposeDrawer>
  );
}

// ----------------------------------------------------------------------------------------------- merge

function MergeDrawer({ open, person, onClose }: { open: boolean; person: PersonView; onClose: () => void }) {
  const { t } = useTranslation('people-person');
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const [form] = Form.useForm<{ duplicatePersonId: UUID; reason: string }>();
  const duplicate = Form.useWatch('duplicatePersonId', form) ?? null;
  const preview = useMergePreview(person.id, duplicate);
  const merge = useMergePerson(person.id);

  useEffect(() => {
    if (open) {
      form.resetFields();
      merge.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, form]);

  const doMerge = async () => {
    let values: { duplicatePersonId: UUID; reason: string };
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await merge.mutateAsync({ duplicatePersonId: values.duplicatePersonId, reason: values.reason.trim(), version: person.version });
      fb.done(t('person.merged'));
      onClose();
    } catch {
      /* shown in the drawer */
    }
  };

  const p = preview.data;
  const list = (title: string, items: string[], danger = false) =>
    items.length > 0 ? (
      <List
        size="small"
        header={<Typography.Text strong type={danger ? 'danger' : undefined}>{title}</Typography.Text>}
        dataSource={items}
        renderItem={(i) => <List.Item>{i}</List.Item>}
        style={{ marginBottom: 8 }}
      />
    ) : null;

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={t('person.mergeTitle')}
      width={600}
      destroyOnHidden
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>{tc('action.cancel')}</Button>
          <ConfirmButton danger type="primary" disabled={!p?.mergeable} loading={merge.isPending} title={t('person.mergeHint')} onConfirm={doMerge}>
            {t('person.mergeSubmit')}
          </ConfirmButton>
        </Space>
      }
    >
      {merge.error || preview.error ? <ProblemAlert error={merge.error ?? preview.error} style={{ marginBottom: 12 }} /> : null}
      <Alert type="warning" showIcon message={t('person.mergeHint')} style={{ marginBottom: 12 }} />
      <Form form={form} layout="vertical">
        <Form.Item name="duplicatePersonId" label={t('person.duplicate')} rules={[{ required: true, message: t('person.duplicateRequired') }]}>
          <EmployeeSelect valueField="personId" />
        </Form.Item>
        <Form.Item name="reason" label={t('person.mergeReason')} rules={[{ required: true, whitespace: true, message: t('person.mergeReasonRequired') }, { max: 500 }]}>
          <Input.TextArea rows={2} maxLength={500} />
        </Form.Item>
      </Form>
      {preview.isFetching ? <Skeleton active /> : null}
      {p ? (
        <div>
          {!p.mergeable ? <Alert type="error" showIcon message={t('person.notMergeable')} style={{ marginBottom: 8 }} /> : null}
          {list(t('person.conflicts'), p.conflicts, true)}
          {list(t('person.rowsToMove'), Object.entries(p.rowsToMove).map(([table, n]) => `${table}: ${n}`))}
          {list(t('person.identifiersTaken'), p.identifiersTaken)}
          {list(t('person.adjustments'), p.adjustments)}
        </div>
      ) : null}
    </Drawer>
  );
}
