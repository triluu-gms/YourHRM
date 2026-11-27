import { registerNamespace } from '@/shared/i18n';
import '../../common/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-overtime`: overtime requests, consent, results per category, caps and the annual regime. */
export const OVERTIME_NS = 'att-overtime';
registerNamespace(OVERTIME_NS, { vi, en });
