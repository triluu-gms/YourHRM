import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `people-employment`: employment facts, primary employment, insurance, assignments, salary. */
export const EMPLOYMENT_NS = 'people-employment';
registerNamespace(EMPLOYMENT_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
