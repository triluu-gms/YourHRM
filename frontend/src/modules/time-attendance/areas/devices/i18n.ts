import { registerNamespace } from '@/shared/i18n';
import '../../common/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `att-devices`: time clocks, enrolment IDs and the punch quarantine. */
export const DEVICES_NS = 'att-devices';
registerNamespace(DEVICES_NS, { vi, en });
