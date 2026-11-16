import dayjs from 'dayjs';
import { ISO_DATE, formatDate, formatNumber } from '@/shared/format';
import { MASKED, OPEN_FROM, type Setting, type SettingJson, type SettingValueType } from './api';

// ------------------------------------------------------------------------------------------------ number patterns

/** Mirrors `SequenceService.TOKEN` / `TOKENS`. */
const TOKEN = /\{([A-Z]+)(?::(\d+))?}/g;
export const PATTERN_TOKENS = ['{SEQ:n}', '{YYYY}', '{YY}', '{MM}', '{LE}'] as const;
const KNOWN = ['SEQ', 'YYYY', 'YY', 'MM', 'LE'];

/** Mirrors the backend `@Pattern` on `CreateSequenceRequest.code`. */
export const SEQUENCE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{1,40}$/;

export type PatternProblem = 'unknownToken' | 'seqWidth' | 'seqCount';

/** Same rules as `SequenceService.validate`: known tokens only, exactly one `{SEQ:n}` with 1 ≤ n ≤ 12. */
export function checkPattern(pattern: string): { problem: PatternProblem; token?: string } | null {
  let seq = 0;
  for (const m of pattern.matchAll(TOKEN)) {
    if (!KNOWN.includes(m[1] ?? '')) return { problem: 'unknownToken', token: m[0] };
    if (m[1] === 'SEQ') {
      seq++;
      const width = m[2] === undefined ? NaN : Number(m[2]);
      if (!(width >= 1 && width <= 12)) return { problem: 'seqWidth' };
    }
  }
  return seq === 1 ? null : { problem: 'seqCount' };
}

/** Same output as `SequenceService.format`; for the live sample while editing a pattern (no server round trip). */
export function formatPattern(pattern: string, value: number, date: string, leCode: string | null): string {
  const d = dayjs(date);
  return pattern.replace(TOKEN, (whole, token: string, width?: string) => {
    switch (token) {
      case 'SEQ':
        return String(value).padStart(width ? Number(width) : 0, '0');
      case 'YYYY':
        return String(d.year()).padStart(4, '0');
      case 'YY':
        return String(d.year() % 100).padStart(2, '0');
      case 'MM':
        return String(d.month() + 1).padStart(2, '0');
      case 'LE':
        return leCode ?? '';
      default:
        return whole;
    }
  });
}

/** True when the series needs a legal entity to produce a number (`SequenceService.legalEntityCode`). */
export const needsLegalEntity = (scopeLevel: string, pattern: string) =>
  scopeLevel === 'LEGAL_ENTITY' || pattern.includes('{LE}');

/** True when the series needs a business date (`core.next_sequence_value`). */
export const needsDate = (resetRule: string, pattern: string) =>
  resetRule !== 'NEVER' || /\{(YYYY|YY|MM)}/.test(pattern);

// ------------------------------------------------------------------------------------------------ setting values

export const isMasked = (value: SettingJson) => value === MASKED;

/** One-line display of a value. Booleans are rendered by the caller (translated), the rest here. */
export function settingValueText(type: SettingValueType, value: SettingJson): string {
  if (value === null || value === undefined) return '';
  if (isMasked(value)) return MASKED;
  switch (type) {
    case 'INT':
    case 'DECIMAL':
      return typeof value === 'number' ? formatNumber(value) : String(value);
    case 'DATE':
      return typeof value === 'string' ? formatDate(value) : String(value);
    case 'JSON':
      return JSON.stringify(value);
    default:
      return String(value);
  }
}

/** The form field's value for a stored value: dayjs for dates, pretty JSON text for JSON, the value otherwise. */
export function toEditorValue(type: SettingValueType, value: SettingJson): unknown {
  if (value === null || value === undefined || isMasked(value)) return undefined;
  if (type === 'DATE') return typeof value === 'string' ? dayjs(value) : undefined;
  if (type === 'JSON') return JSON.stringify(value, null, 2);
  return value;
}

/** The request's value from the form field; JSON text has been checked by the form rule already. */
export function fromEditorValue(type: SettingValueType, editor: unknown): SettingJson {
  switch (type) {
    case 'BOOL':
      return editor === true;
    case 'DATE':
      return dayjs.isDayjs(editor) ? editor.format(ISO_DATE) : (editor as string);
    case 'JSON':
      return JSON.parse(String(editor)) as SettingJson;
    case 'TEXT':
      return String(editor ?? '');
    default:
      return editor as SettingJson;
  }
}

export function isValidJson(text: unknown): boolean {
  if (typeof text !== 'string' || !text.trim()) return false;
  try {
    JSON.parse(text);
    return true;
  } catch {
    return false;
  }
}

/** Non-dated values start on 1900-01-01: they are "always", not a real start date. */
export const isOpenFrom = (validFrom: string | null | undefined) => !validFrom || validFrom === OPEN_FROM;

/** Bilingual description: the UI language first. */
export const settingDescription = (s: Pick<Setting, 'descriptionVi' | 'descriptionEn'>, language: string) =>
  (language.startsWith('en') ? s.descriptionEn : s.descriptionVi) || s.descriptionVi || s.descriptionEn;
