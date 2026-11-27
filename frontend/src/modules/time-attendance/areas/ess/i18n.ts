import { registerNamespace } from '@/shared/i18n';
import '../../common/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-ess`: the employee's own timesheet, schedule, leave, overtime and requests. */
export const ESS_NS = 'att-ess';
registerNamespace(ESS_NS, { vi, en });
