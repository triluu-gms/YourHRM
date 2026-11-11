import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `people-person`: person, addresses, contacts, identity documents, qualifications, hire wizard. */
export const PERSON_NS = 'people-person';
registerNamespace(PERSON_NS, { vi, en });
registerProblemMessages({ vi: vi.errors, en: en.errors });
