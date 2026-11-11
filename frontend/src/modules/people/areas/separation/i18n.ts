import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `people-separation`: separations, notice / severance assessment, service periods, DSR actions. */
export const SEPARATION_NS = 'people-separation';
registerNamespace(SEPARATION_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
