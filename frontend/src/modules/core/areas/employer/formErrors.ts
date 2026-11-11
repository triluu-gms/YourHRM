import type { FormInstance } from 'antd';
import { isProblem } from '@/shared/api';

/**
 * Maps a `validation-failed` problem onto the form (`attributes.taxCode` → `['attributes', 'taxCode']`).
 * `rename` maps backend field paths onto differently named form fields (e.g. `attributes.oaOdRate`, a fraction in
 * the API, is edited as `attributes.oaOdRatePercent`). Returns true when the error was consumed; otherwise the caller
 * shows it with `<ProblemAlert>`.
 */
export function applyFieldErrors(form: FormInstance, error: unknown, rename: Record<string, string> = {}): boolean {
  if (!isProblem(error, 'validation-failed') || !error.problem.errors?.length) return false;
  form.setFields(
    error.problem.errors.map((e) => ({ name: (rename[e.field] ?? e.field).split('.'), errors: [e.message] })),
  );
  return true;
}

export const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Mirrors the backend `@Pattern` of legal entity / location / org unit codes. */
export const CODE_PATTERN = /^[A-Za-z0-9_.-]{1,32}$/;

/** MST (BR-CORE-003): 10 digits, or 13 as `NNNNNNNNNN-NNN` for a dependent unit. The backend stays authoritative. */
export const TAX_CODE_PATTERN = /^[0-9]{10}(-[0-9]{3})?$/;

/** Empty strings and undefined from Ant inputs become null so optional backend fields are cleared, not sent as "". */
export function blankToNull<T extends object>(values: T): T {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values)) {
    out[k] = v === undefined || (typeof v === 'string' && v.trim() === '') ? null : typeof v === 'string' ? v.trim() : v;
  }
  return out as T;
}
