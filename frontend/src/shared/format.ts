import dayjs from 'dayjs';
import { DISPLAY_TIME_ZONE } from './i18n';

/** Display conventions (architecture-overview §11.3, ADR-0034): dates dd/MM/yyyy, VND without decimals. */
export const DATE_FORMAT = 'DD/MM/YYYY';
export const DATE_TIME_FORMAT = 'DD/MM/YYYY HH:mm';
export const ISO_DATE = 'YYYY-MM-DD';

export function formatDate(value: string | null | undefined): string {
  if (!value) return '';
  return dayjs(value).format(DATE_FORMAT);
}

/** Instants are UTC on the wire and shown in Asia/Ho_Chi_Minh. */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '';
  return dayjs(value).tz(DISPLAY_TIME_ZONE).format(DATE_TIME_FORMAT);
}

/** Pattern A range; `validTo` exclusive → displayed inclusive (day before). */
export function formatValidity(validFrom: string, validTo: string | null | undefined, openEnded: string): string {
  const to = validTo ? formatDate(dayjs(validTo).subtract(1, 'day').format(ISO_DATE)) : openEnded;
  return `${formatDate(validFrom)} – ${to}`;
}

export function formatMoney(value: number | string | null | undefined, currency = 'VND'): string {
  if (value === null || value === undefined || value === '') return '';
  const n = typeof value === 'string' ? Number(value) : value;
  if (Number.isNaN(n)) return String(value);
  const digits = currency === 'VND' ? 0 : 2;
  return new Intl.NumberFormat('vi-VN', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(n);
}

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) return '';
  return new Intl.NumberFormat('vi-VN').format(value);
}

/** Bilingual names: the current UI language first, the other as fallback. */
export function localizedName(
  item: { nameVi?: string | null; nameEn?: string | null } | null | undefined,
  language: string,
): string {
  if (!item) return '';
  const primary = language.startsWith('en') ? item.nameEn : item.nameVi;
  return primary || item.nameVi || item.nameEn || '';
}

export function today(): string {
  return dayjs().format(ISO_DATE);
}
