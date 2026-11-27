import { registerNamespace } from '@/shared/i18n';
import '../../common/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-schedules`: shifts, weekly patterns, assignment rules, schedule assignments, STD_DAYS. */
export const SCHEDULES_NS = 'att-schedules';
registerNamespace(SCHEDULES_NS, { vi, en });
