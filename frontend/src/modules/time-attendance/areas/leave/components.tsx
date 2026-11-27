import { Select, Tag, Tooltip, type FormInstance, type SelectProps } from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { ApiProblem, type ISODate, type UUID } from '@/shared/api';
import { StatusTag } from '@/shared/components';
import { ISO_DATE, localizedName } from '@/shared/format';
import { legalEntityLabel, useLegalEntities } from '../../common';
import type { LeaveTypeRow } from './api';
import { useLeaveTypes } from './queries';
import { LEAVE_NS } from './i18n';

export const NS = LEAVE_NS;

/** Quantities (days / hours, BigDecimal): up to 2 decimals, vi-VN grouping. */
export function formatQty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '';
  return new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 }).format(value);
}

export const toIso = (d: Dayjs | null | undefined): ISODate | null => (d ? d.format(ISO_DATE) : null);
export const fromIso = (s: string | null | undefined): Dayjs | null => (s ? dayjs(s) : null);
/** Pattern A: the form asks for the last valid day; the wire carries the exclusive `validTo` (day after). */
export const lastDayToValidTo = (d: Dayjs | null | undefined): ISODate | null => (d ? d.add(1, 'day').format(ISO_DATE) : null);

/** Translated code of one of this area's enums (`enum.<group>.<CODE>`), the code itself when unknown. */
export function useEnumLabel() {
  const { t } = useTranslation(NS);
  return (group: string, code: string | null | undefined) => (code ? t(`enum.${group}.${code}`, { defaultValue: code }) : '');
}

export function enumOptions(label: (g: string, c: string) => string, group: string, codes: readonly string[]) {
  return codes.map((c) => ({ value: c, label: label(group, c) }));
}

/** Leave request status, with WITHDRAWN translated by this area. */
export function RequestStatusTag({ status }: { status: string }) {
  const label = useEnumLabel();
  return <StatusTag status={status} labels={{ [status]: label('requestStatus', status) }} />;
}

export function typeLabel(t: LeaveTypeRow, language: string): string {
  return `${t.data.code} – ${localizedName(t.data, language)}`;
}

/** Leave types as a `Form.Item` child; the value is the leave type id. */
export function LeaveTypeSelect({ activeOnly, ...props }: Omit<SelectProps<UUID>, 'options' | 'loading'> & { activeOnly?: boolean }) {
  const { i18n } = useTranslation();
  const q = useLeaveTypes();
  const options = (q.data ?? [])
    .filter((lt) => !activeOnly || lt.data.status === 'ACTIVE')
    .map((lt) => ({ value: lt.id, label: typeLabel(lt, i18n.language) }));
  return <Select<UUID> showSearch optionFilterProp="label" allowClear loading={q.isLoading} options={options} {...props} />;
}

/** Name of a leave type by id (falls back to the code carried by the row). */
export function LeaveTypeName({ id, code }: { id: UUID; code?: string | null }) {
  const { i18n } = useTranslation();
  const q = useLeaveTypes();
  const lt = q.data?.find((x) => x.id === id);
  return <>{lt ? typeLabel(lt, i18n.language) : (code ?? id)}</>;
}

export function LegalEntityName({ id }: { id: UUID | null | undefined }) {
  const { t, i18n } = useTranslation(NS);
  const q = useLegalEntities();
  if (!id) return <>{t('common.allLegalEntities')}</>;
  const le = q.data?.find((x) => x.id === id);
  return <>{le ? legalEntityLabel(le, i18n.language) : id}</>;
}

/** Employment ids have no name lookup by id in T&A yet: show a short id with the full one in a tooltip. */
export function EmploymentRef({ id }: { id: UUID }) {
  return (
    <Tooltip title={id}>
      <Tag style={{ fontFamily: 'monospace' }}>{id.slice(-8)}</Tag>
    </Tooltip>
  );
}

/** Puts `validation-failed` field errors on the form fields with the same name. */
export function applyFieldErrors(form: FormInstance, error: unknown) {
  if (!(error instanceof ApiProblem) || !error.problem.errors?.length) return;
  form.setFields(error.problem.errors.map((e) => ({ name: e.field.split('.'), errors: [e.message] })));
}

/** Translates a preview warning code (`insufficient-balance 2026`, `eligibility-gender-unverified`). */
export function useWarningText() {
  const { t } = useTranslation(NS);
  return (w: string) => {
    const [code, ...rest] = w.split(' ');
    return t(`warning.${code}`, { defaultValue: w, arg: rest.join(' ') });
  };
}
