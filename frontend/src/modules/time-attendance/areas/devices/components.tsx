import { useMemo } from 'react';
import { Select, Tag, type FormInstance, type SelectProps } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { isProblem, type UUID } from '@/shared/api';
import { legalEntityLabel, lookupApi, useLegalEntities, useLocations } from '../../common';
import { useDevices } from './queries';
import { DEVICES_NS } from './i18n';

export const NS = DEVICES_NS;

/** Ant `Select` of the registered time clocks (value = device id). */
export function DeviceSelect(props: Omit<SelectProps<UUID>, 'options' | 'loading'>) {
  const q = useDevices();
  const options = (q.data ?? []).map((d) => ({ value: d.id, label: `${d.code} – ${d.name}` }));
  return <Select<UUID> showSearch optionFilterProp="label" allowClear loading={q.isLoading} options={options} {...props} />;
}

/** Device id → code, from the (cached) device list. */
export function useDeviceCodes(): Map<UUID, string> {
  const q = useDevices();
  return useMemo(() => new Map((q.data ?? []).map((d) => [d.id, d.code])), [q.data]);
}

/**
 * Employment id → "NV001 – Name". People has no lookup by ids, so this reads one page of the directory (scoped by the
 * backend) and falls back to the shortened id. A shared `EmployeeName` in `common` would replace it.
 */
export function useEmployeeLabels(): Map<UUID, string> {
  const q = useQuery({
    queryKey: ['att', 'lookup', 'employee-directory'],
    queryFn: () => lookupApi.employees({ size: 200 }),
    staleTime: 5 * 60_000,
  });
  return useMemo(
    () => new Map((q.data?.items ?? []).map((e) => [e.employmentId, `${e.employeeNo} – ${e.fullName}`])),
    [q.data],
  );
}

export const shortId = (id: string | null | undefined) => (id ? id.slice(0, 8) : '');

export function LegalEntityName({ id }: { id: UUID | null | undefined }) {
  const { i18n } = useTranslation();
  const q = useLegalEntities();
  if (!id) return null;
  const le = q.data?.find((x) => x.id === id);
  return <>{le ? legalEntityLabel(le, i18n.language) : shortId(id)}</>;
}

export function LocationName({ id }: { id: UUID | null | undefined }) {
  const q = useLocations();
  if (!id) return null;
  const l = q.data?.find((x) => x.id === id);
  return <>{l ? (l.current?.name ? `${l.code} – ${l.current.name}` : l.code) : shortId(id)}</>;
}

const PUNCH_COLOURS: Record<string, string> = {
  VALID: 'green',
  IGNORED: 'default',
  PENDING: 'gold',
  QUARANTINED_UNMAPPED: 'red',
  QUARANTINED_EXPIRED: 'orange',
  QUARANTINED_UNKNOWN_DEVICE: 'volcano',
};

export function PunchStatusTag({ status }: { status: string }) {
  const { t } = useTranslation(NS);
  return <Tag color={PUNCH_COLOURS[status] ?? 'default'}>{t(`punchStatus.${status}`, { defaultValue: status })}</Tag>;
}

const DEVICE_COLOURS: Record<string, string> = { ACTIVE: 'green', INACTIVE: 'default', RETIRED: 'orange' };

export function DeviceStatusTag({ status }: { status: string }) {
  const { t } = useTranslation(NS);
  return <Tag color={DEVICE_COLOURS[status] ?? 'default'}>{t(`deviceStatus.${status}`, { defaultValue: status })}</Tag>;
}

/** `validation-failed` field errors onto the Ant form; true when handled. */
export function applyFieldErrors(form: FormInstance, error: unknown): boolean {
  if (!isProblem(error, 'validation-failed') || !error.problem.errors?.length) return false;
  form.setFields(error.problem.errors.map((e) => ({ name: e.field.split('.').pop() ?? e.field, errors: [e.message] })));
  return true;
}

/** Trimmed text or null (optional backend fields are cleared, not sent as ""). */
export const textOrNull = (v: string | null | undefined): string | null => v?.trim() || null;
