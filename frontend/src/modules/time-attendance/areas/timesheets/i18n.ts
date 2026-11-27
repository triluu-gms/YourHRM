import { registerNamespace } from '@/shared/i18n';
import '../../common/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-timesheets`: attendance codes, daily timesheets, corrections, menstrual-break declarations. */
export const TIMESHEETS_NS = 'att-timesheets';
registerNamespace(TIMESHEETS_NS, { vi, en });
