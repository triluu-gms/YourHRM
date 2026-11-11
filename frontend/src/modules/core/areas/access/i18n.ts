import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-access`: users, role assignments, roles + permission matrix, permission catalogue, support sessions. */
export const ACCESS_NS = 'core-access';
registerNamespace(ACCESS_NS, { vi, en });
/** Problem codes of the user / role / assignment endpoints, shown by `ProblemAlert` and `useFeedback`. */
registerProblemMessages({ vi: vi.errors, en: en.errors });
