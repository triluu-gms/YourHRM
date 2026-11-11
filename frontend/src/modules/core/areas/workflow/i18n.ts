import { registerNamespace } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-workflow`: `useTranslation('core-workflow')`. */
export const WORKFLOW_NS = 'core-workflow';
registerNamespace(WORKFLOW_NS, { vi, en });
