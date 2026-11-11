import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-employer`: company group, legal entities, locations, org unit types and the org tree. */
export const EMPLOYER_NS = 'core-employer';
registerNamespace(EMPLOYER_NS, { vi, en });
/** Problem codes of the employer-structure endpoints, shown by `ProblemAlert` and `useFeedback`. */
registerProblemMessages({ vi: vi.errors, en: en.errors });
