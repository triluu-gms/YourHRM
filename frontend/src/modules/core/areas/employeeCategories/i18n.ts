import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-employee-categories`: the tenant's employee category code list (BR-CORE-026). */
export const EMPLOYEE_CATEGORIES_NS = 'core-employee-categories';
registerNamespace(EMPLOYEE_CATEGORIES_NS, { vi, en });
/** Problem codes of `EmployeeCategoryController`, shown by `ProblemAlert` and `useFeedback`. */
registerProblemMessages({ vi: vi.errors, en: en.errors });
