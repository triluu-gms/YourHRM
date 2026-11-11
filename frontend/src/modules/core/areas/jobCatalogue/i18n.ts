import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-job-catalogue`: job families, job titles, grade structures and grades. */
export const JOB_CATALOGUE_NS = 'core-job-catalogue';
registerNamespace(JOB_CATALOGUE_NS, { vi, en });
/** Problem codes of `JobCatalogueController`, shown by `ProblemAlert` and `useFeedback`. */
registerProblemMessages({ vi: vi.errors, en: en.errors });
