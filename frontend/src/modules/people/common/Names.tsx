import { Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { localizedName } from '@/shared/format';
import {
  codeLabel,
  legalEntityLabel,
  useAllGrades,
  useCodeValues,
  useCountries,
  useJobTitles,
  useLegalEntities,
  useLocations,
  useOrgUnits,
} from './lookups';
import type { EmploymentStatus } from './types';
import './i18n';

const Dash = () => <Typography.Text type="secondary">—</Typography.Text>;

/** Renders the name of a Core object from its id (cached lookups); the short id while loading or when unknown. */
export function LegalEntityName({ id }: { id: UUID | null | undefined }) {
  const { i18n } = useTranslation();
  const q = useLegalEntities();
  if (!id) return <Dash />;
  const le = q.data?.find((x) => x.id === id);
  return <>{le ? legalEntityLabel(le, i18n.language) : shortId(id)}</>;
}

export function OrgUnitName({ id }: { id: UUID | null | undefined }) {
  const { i18n } = useTranslation();
  const q = useOrgUnits();
  if (!id) return <Dash />;
  const ou = q.data?.find((x) => x.id === id);
  return <>{ou ? `${ou.code} – ${localizedName(ou, i18n.language)}` : shortId(id)}</>;
}

export function LocationName({ id }: { id: UUID | null | undefined }) {
  const q = useLocations();
  if (!id) return <Dash />;
  const l = q.data?.find((x) => x.id === id);
  return <>{l ? `${l.code} – ${l.current?.name ?? ''}` : shortId(id)}</>;
}

export function JobTitleName({ id }: { id: UUID | null | undefined }) {
  const { i18n } = useTranslation();
  const q = useJobTitles();
  if (!id) return <Dash />;
  const j = q.data?.find((x) => x.id === id);
  return <>{j ? `${j.code} – ${localizedName(j, i18n.language)}` : shortId(id)}</>;
}

export function GradeName({ id }: { id: UUID | null | undefined }) {
  const { grades } = useAllGrades();
  if (!id) return <Dash />;
  const g = grades.find((x) => x.id === id);
  return <>{g ? `${g.code} – ${g.name}` : shortId(id)}</>;
}

/** Label of a code from a Core code list (`/core/master-data/code-lists/{list}/values`). */
export function CodeLabel({ list, code }: { list: string; code: string | null | undefined }) {
  const { i18n } = useTranslation();
  const q = useCodeValues(code ? list : null);
  if (!code) return <Dash />;
  return <>{codeLabel(q.data?.find((v) => v.valueCode === code), code, i18n.language)}</>;
}

export function CountryName({ code, nationality = false }: { code: string | null | undefined; nationality?: boolean }) {
  const { i18n } = useTranslation();
  const q = useCountries();
  if (!code) return <Dash />;
  const c = q.data?.find((x) => x.code === code);
  if (!c) return <>{code}</>;
  const en = i18n.language.startsWith('en');
  const label = nationality ? (en ? c.nationalityEn : c.nationalityVi) : en ? c.nameEn : c.nameVi;
  return <>{label || c.nameVi || code}</>;
}

const STATUS_COLOURS: Record<EmploymentStatus, string> = {
  PRE_HIRE: 'blue',
  ACTIVE: 'green',
  SUSPENDED: 'orange',
  TERMINATED: 'default',
  CANCELLED: 'red',
};

export function EmploymentStatusTag({ status }: { status: EmploymentStatus | null | undefined }) {
  const { t } = useTranslation('people');
  if (!status) return null;
  return <Tag color={STATUS_COLOURS[status] ?? 'default'}>{t(`employmentStatus.${status}`, { defaultValue: status })}</Tag>;
}

/** Monospace masked identifier as returned by the API (e.g. `••••••••1234`); never unmasked client-side. */
export function MaskedValue({ value }: { value: string | null | undefined }) {
  if (!value) return <Dash />;
  return <Typography.Text code>{value}</Typography.Text>;
}

export function shortId(id: string): string {
  return `${id.slice(0, 8)}…`;
}
