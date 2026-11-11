import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `people-family`: family members, PIT dependants, salary bank accounts. */
export const FAMILY_NS = 'people-family';
registerNamespace(FAMILY_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
