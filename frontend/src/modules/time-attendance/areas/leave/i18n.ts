import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-leave`: leave types, policies, requests, balances, ledger, settlement and the leave runs. */
export const LEAVE_NS = 'att-leave';
registerNamespace(LEAVE_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
