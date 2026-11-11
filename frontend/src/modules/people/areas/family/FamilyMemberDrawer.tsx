import { useEffect } from 'react';
import { Checkbox, DatePicker, Form, Input, Select } from 'antd';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { FormDrawer, MoneyInput, useFeedback } from '@/shared/components';
import { DATE_FORMAT } from '@/shared/format';
import { applyFieldErrors, CodeValueSelect, fromIsoDate, toIsoDate } from '../../common';
import type { FamilyMemberRequest, FamilyMemberView } from './api';
import { useSaveFamilyMember } from './queries';
import './i18n';

/** Core code list of family relationships (validated by `FamilyMemberService`). */
export const RELATIONSHIP_LIST = 'RELATIONSHIP';

interface FormValues {
  relationshipCode: string;
  fullName: string;
  birthDate?: Dayjs | null;
  nationalId?: string;
  taxCode?: string;
  hasIncomeAboveThreshold?: 'yes' | 'no';
  averageMonthlyIncome?: number | null;
  disabled?: boolean;
  deceased?: boolean;
}

/** Create (no `member`) or edit a family member; on edit, empty identifier fields keep the stored (masked) values. */
export function FamilyMemberDrawer({
  personId,
  open,
  member,
  onClose,
}: {
  personId: UUID;
  open: boolean;
  member?: FamilyMemberView | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { t: tf } = useTranslation('people-family');
  const fb = useFeedback();
  const [form] = Form.useForm<FormValues>();
  const save = useSaveFamilyMember(personId);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    save.reset();
    if (member) {
      form.setFieldsValue({
        relationshipCode: member.relationshipCode,
        fullName: member.fullName,
        birthDate: fromIsoDate(member.birthDate),
        hasIncomeAboveThreshold:
          member.hasIncomeAboveThreshold === true ? 'yes' : member.hasIncomeAboveThreshold === false ? 'no' : undefined,
        disabled: !!member.disabled,
        deceased: member.deceased,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset only when the drawer (re)opens
  }, [open, member, form]);

  const submit = async () => {
    let v: FormValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const body: FamilyMemberRequest = {
      relationshipCode: v.relationshipCode,
      fullName: v.fullName.trim(),
      birthDate: toIsoDate(v.birthDate),
      // null keeps the stored value on update; on create null simply means "not given"
      nationalId: v.nationalId?.trim() || null,
      taxCode: v.taxCode?.trim() || null,
      hasIncomeAboveThreshold: v.hasIncomeAboveThreshold === undefined ? null : v.hasIncomeAboveThreshold === 'yes',
      averageMonthlyIncome: v.averageMonthlyIncome ?? null,
      // a masked view hides the disability flag: do not overwrite it unless the user could see it
      disabled: member?.masked && member.disabled == null ? null : !!v.disabled,
      deceased: !!v.deceased,
      version: member?.version ?? null,
    };
    try {
      await save.mutateAsync({ id: member?.id, body });
      if (member) fb.saved();
      else fb.created();
      onClose();
    } catch (e) {
      applyFieldErrors(form, e);
    }
  };

  const keep = member ? tf('members.keepHint') : undefined;

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={member ? tf('members.edit') : tf('members.add')}
      onSubmit={() => void submit()}
      submitting={save.isPending}
      error={save.error}
    >
      <Form<FormValues> form={form} layout="vertical">
        <Form.Item name="relationshipCode" label={tf('members.relationship')} rules={[{ required: true, message: tf('members.relationshipRequired') }]}>
          <CodeValueSelect list={RELATIONSHIP_LIST} />
        </Form.Item>
        <Form.Item
          name="fullName"
          label={tf('members.fullName')}
          rules={[{ required: true, whitespace: true, message: tf('members.nameRequired') }, { max: 200 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item name="birthDate" label={tf('members.birthDate')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="nationalId" label={tf('members.nationalId')} extra={keep} rules={[{ max: 32 }]}>
          <Input autoComplete="off" placeholder={member?.nationalIdMasked ?? undefined} />
        </Form.Item>
        <Form.Item name="taxCode" label={tf('members.taxCode')} extra={keep} rules={[{ max: 20 }]}>
          <Input autoComplete="off" placeholder={member?.taxCodeMasked ?? undefined} />
        </Form.Item>
        <Form.Item name="hasIncomeAboveThreshold" label={tf('members.income')}>
          <Select
            allowClear
            options={[
              { value: 'yes', label: t('common.yes') },
              { value: 'no', label: t('common.no') },
            ]}
          />
        </Form.Item>
        <Form.Item name="averageMonthlyIncome" label={tf('members.averageIncome')} extra={tf('members.averageIncomeHint')}>
          <MoneyInput />
        </Form.Item>
        <Form.Item name="disabled" valuePropName="checked">
          <Checkbox>{tf('members.disabled')}</Checkbox>
        </Form.Item>
        <Form.Item name="deceased" valuePropName="checked">
          <Checkbox>{tf('members.deceased')}</Checkbox>
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}
