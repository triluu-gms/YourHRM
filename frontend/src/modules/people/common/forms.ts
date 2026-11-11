import type { FormInstance } from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { isProblem, type ISODate } from '@/shared/api';
import { ISO_DATE } from '@/shared/format';

/**
 * Maps a `validation-failed` problem onto the Ant form (`errors[].field` → field name, last path segment wins).
 * Returns true when handled; other problems are left for the drawer's `ProblemAlert`.
 */
export function applyFieldErrors(form: FormInstance, error: unknown): boolean {
  if (!isProblem(error, 'validation-failed') || !error.problem.errors?.length) return false;
  form.setFields(error.problem.errors.map((e) => ({ name: e.field.split('.').pop() ?? e.field, errors: [e.message] })));
  return true;
}

export const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Empty strings from Ant inputs become null so optional backend fields are cleared, not sent as "". */
export function blankToNull<T extends Record<string, unknown>>(values: T): T {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values)) out[k] = typeof v === 'string' && v.trim() === '' ? null : v;
  return out as T;
}

/** Dayjs (DatePicker) → `YYYY-MM-DD` or null. */
export function toIsoDate(value: Dayjs | null | undefined): ISODate | null {
  return value ? value.format(ISO_DATE) : null;
}

/** `YYYY-MM-DD` → Dayjs for a DatePicker initial value. */
export function fromIsoDate(value: ISODate | null | undefined): Dayjs | null {
  return value ? dayjs(value) : null;
}

/** Multipart body with a file and optional JSON/plain fields (sign, evidence, receipt uploads). */
export function fileForm(file: File, fields: Record<string, string | null | undefined> = {}, fileField = 'file'): FormData {
  const form = new FormData();
  form.append(fileField, file);
  for (const [k, v] of Object.entries(fields)) if (v !== null && v !== undefined && v !== '') form.append(k, v);
  return form;
}

/** Fresh `Idempotency-Key` for side-effecting POSTs (ADR-0014). */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
