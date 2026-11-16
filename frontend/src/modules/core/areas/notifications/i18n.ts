import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-notifications`: inbox, delivery monitor and template registry. */
export const NOTIFICATIONS_NS = 'core-notifications';
registerNamespace(NOTIFICATIONS_NS, { vi, en });
/** Problem codes of `NotificationController` / `NotificationService.resend`, shown by `ProblemAlert` and `useFeedback`. */
registerProblemMessages({ vi: vi.errors, en: en.errors });
