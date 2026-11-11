import dayjs, { type Dayjs } from 'dayjs';
import type { ISODate } from '@/shared/api';
import type { EmployeeProfile } from '../../common';

/** DatePicker (month) → `yyyy-MM` as the backend `YearMonth` expects. */
export const toYearMonth = (d: Dayjs | null | undefined): string | null => (d ? d.format('YYYY-MM') : null);

/** First-of-month ISO date from the API → `MM/YYYY`. */
export const formatMonth = (iso: ISODate | null | undefined): string => (iso ? dayjs(iso).format('MM/YYYY') : '');

export const MONTH_FORMAT = 'MM/YYYY';

/** The employments of the profile's person (this one first), as select options labelled by employee number. */
export function employmentOptions(profile: EmployeeProfile) {
  return [profile.employment, ...profile.otherEmployments].map((e) => ({ value: e.employmentId, label: e.employeeNo }));
}
