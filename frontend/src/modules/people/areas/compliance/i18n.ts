import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `people-compliance`: tax residency + VN presence, work permits, special statuses, expiring documents. */
export const COMPLIANCE_NS = 'people-compliance';
registerNamespace(COMPLIANCE_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
