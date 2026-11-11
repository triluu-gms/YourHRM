import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `people-contracts`: labour contracts, appendices, suspensions, decisions, expiry lists. */
export const CONTRACTS_NS = 'people-contracts';
registerNamespace(CONTRACTS_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
