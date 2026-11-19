import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-cost-centres`: the cost centres of each legal entity (BR-CORE-025). */
export const COST_CENTRES_NS = 'core-cost-centres';
registerNamespace(COST_CENTRES_NS, { vi, en });
/** Problem codes of `CostCentreController`, shown by `ProblemAlert` and `useFeedback`. */
registerProblemMessages({ vi: vi.errors, en: en.errors });
