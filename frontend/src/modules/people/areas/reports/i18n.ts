import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `people-reports`: statutory reports, labour book, dependant registrations, opening balance, integration inbox. */
export const REPORTS_NS = 'people-reports';
registerNamespace(REPORTS_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
