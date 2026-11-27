import { registerNamespace } from '@/shared/i18n';
import '../../common/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-periods`: attendance periods, checklist, close / reopen, results, holds, retro, result imports. */
export const PERIODS_NS = 'att-periods';
registerNamespace(PERIODS_NS, { vi, en });
