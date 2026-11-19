import { registerNamespace } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-master-data`: the read-only browser over the global reference data (BR-CORE-080..082). */
export const MASTER_DATA_NS = 'core-master-data';
registerNamespace(MASTER_DATA_NS, { vi, en });
