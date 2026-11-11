import type { CSSProperties } from 'react';
import { Select, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { localizedName } from '@/shared/format';
import type { CodeValue, Grade, JobFamily } from './api';
import { useHazardCategories, useJobFamilies } from './queries';
import { JOB_CATALOGUE_NS } from './i18n';

/** Label of a code value in the UI language (falls back to the other language, then the code). */
export function codeLabel(value: CodeValue | undefined, code: string, language: string): string {
  if (!value) return code;
  const primary = language.startsWith('en') ? value.labelEn : value.labelVi;
  return primary || value.labelVi || value.labelEn || code;
}

export const gradeLabel = (g: Grade) => `${g.code} – ${g.name}`;

export const familyLabel = (f: JobFamily, language: string) => `${f.code} – ${localizedName(f, language)}`;

/** Statutory heavy / hazardous category of a job title (VN-09); "—" style text when not hazardous. */
export function HazardTag({ category }: { category?: string | null }) {
  const { i18n } = useTranslation();
  const { t } = useTranslation(JOB_CATALOGUE_NS);
  const values = useHazardCategories();
  if (!category) return <Typography.Text type="secondary">{t('jobTitle.notHazardous')}</Typography.Text>;
  const value = values.data?.find((v) => v.valueCode === category);
  return <Tag color="volcano">{codeLabel(value, category, i18n.language)}</Tag>;
}

/** Picker over the statutory hazardous categories; controlled by `Form.Item`. Empty = not hazardous. */
export function HazardCategorySelect({
  value,
  onChange,
  id,
}: {
  value?: string | null;
  onChange?: (code: string | null) => void;
  id?: string;
}) {
  const { i18n } = useTranslation();
  const { t } = useTranslation(JOB_CATALOGUE_NS);
  const query = useHazardCategories();
  const options = (query.data ?? [])
    .filter((v) => v.active || v.valueCode === value)
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((v) => ({ value: v.valueCode, label: codeLabel(v, v.valueCode, i18n.language) }));
  if (value && !options.some((o) => o.value === value)) options.unshift({ value, label: value });
  return (
    <Select
      id={id}
      allowClear
      loading={query.isLoading}
      placeholder={t('jobTitle.notHazardous')}
      value={value ?? undefined}
      onChange={(v) => onChange?.(v ?? null)}
      options={options}
    />
  );
}

/** Job family picker: active families plus the current value (even when it has been deactivated since). */
export function JobFamilySelect({
  value,
  onChange,
  id,
  allowClear = true,
  placeholder,
  style,
}: {
  value?: UUID | null;
  onChange?: (id: UUID | null) => void;
  id?: string;
  allowClear?: boolean;
  placeholder?: string;
  style?: CSSProperties;
}) {
  const { i18n } = useTranslation();
  const { t } = useTranslation(JOB_CATALOGUE_NS);
  const query = useJobFamilies(true);
  return (
    <Select
      id={id}
      showSearch
      optionFilterProp="label"
      allowClear={allowClear}
      style={style}
      aria-label={placeholder ?? t('jobTitle.field.family')}
      placeholder={placeholder ?? t('jobTitle.noFamily')}
      loading={query.isLoading}
      value={value ?? undefined}
      onChange={(v) => onChange?.(v ?? null)}
      options={(query.data ?? [])
        .filter((f) => f.active || f.id === value)
        .map((f) => ({
          value: f.id,
          label: f.active ? familyLabel(f, i18n.language) : `${familyLabel(f, i18n.language)} ${t('jobFamily.inactiveSuffix')}`,
        }))}
    />
  );
}
