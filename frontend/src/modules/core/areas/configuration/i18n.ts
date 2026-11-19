import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-configuration`: tenant settings and number series. */
export const CONFIGURATION_NS = 'core-configuration';
registerNamespace(CONFIGURATION_NS, { vi, en });
/** Problem codes of `SettingsController` and `SequenceController`, shown by `ProblemAlert` and `useFeedback`. */
registerProblemMessages({ vi: vi.errors, en: en.errors });
