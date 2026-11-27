import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-reports`: T&A report catalogue (R-01..R-09), integration inbox, manual jobs. */
export const REPORTS_NS = 'att-reports';
registerNamespace(REPORTS_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
