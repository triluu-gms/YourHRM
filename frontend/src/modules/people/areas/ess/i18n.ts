import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `people-ess`: the employee's own pages (profile, family & dependants, bank, documents, resignation). */
export const ESS_NS = 'people-ess';
registerNamespace(ESS_NS, { vi, en });
/** Problem codes of the `/people/me/**` endpoints, shown by `ProblemAlert` and `useFeedback`. */
registerProblemMessages({ vi: vi.errors, en: en.errors });
