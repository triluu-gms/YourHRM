import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-overtime`: overtime requests, consent, results per category, caps and the annual regime. */
export const OVERTIME_NS = 'att-overtime';
registerNamespace(OVERTIME_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
