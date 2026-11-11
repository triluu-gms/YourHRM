import type { FormInstance } from 'antd';
import { isProblem } from '@/shared/api';

/**
 * Maps a `validation-failed` problem onto the form. `rename` maps backend field paths onto the form's field names
 * (the job title form is flat while the API nests the fields under `attributes`). Returns true when consumed;
 * otherwise the drawer's `<ProblemAlert>` shows the translated problem code.
 */
export function applyFieldErrors(form: FormInstance, error: unknown, rename: (field: string) => string = (f) => f): boolean {
  if (!isProblem(error, 'validation-failed') || !error.problem.errors?.length) return false;
  form.setFields(error.problem.errors.map((e) => ({ name: rename(e.field).split('.'), errors: [e.message] })));
  return true;
}

/** `attributes.nameVi` → `nameVi`. */
export const stripAttributes = (field: string) => field.replace(/^attributes\./, '');

/** Mirrors the backend `@Pattern` of job family / job title / grade structure / grade codes. */
export const CODE_PATTERN = /^[A-Za-z0-9_.-]{1,32}$/;

export const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Trimmed text, or null for an empty / missing value (optional backend fields are cleared, not sent as ""). */
export const textOrNull = (v: string | null | undefined): string | null => {
  const s = v?.trim();
  return s ? s : null;
};
