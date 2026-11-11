import type { FormInstance } from 'antd';
import { isProblem } from '@/shared/api';

/**
 * Maps a `validation-failed` problem onto the Ant form (`errors[].field` → field name, last path segment wins so
 * `profile.email` lands on `email`). Returns true when the problem was handled this way.
 */
export function applyFieldErrors(form: FormInstance, error: unknown): boolean {
  if (!isProblem(error, 'validation-failed') || !error.problem.errors?.length) return false;
  form.setFields(
    error.problem.errors.map((e) => ({ name: e.field.split('.').pop() ?? e.field, errors: [e.message] })),
  );
  return true;
}

export const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Empty strings from Ant inputs become null so optional backend fields are cleared, not sent as "". */
export function blankToNull<T extends Record<string, unknown>>(values: T): T {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values)) out[k] = typeof v === 'string' && v.trim() === '' ? null : v;
  return out as T;
}
