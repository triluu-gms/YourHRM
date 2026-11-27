import { registerNamespace } from '@/shared/i18n';
import '../../common/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-calendars`: holiday calendars, dates, template sync and legal-entity assignments. */
export const CALENDARS_NS = 'att-calendars';
registerNamespace(CALENDARS_NS, { vi, en });
