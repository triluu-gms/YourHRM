import { registerNamespace } from '@/shared/i18n';
import vi from './vi.json';
import en from './en.json';

/** Namespace `core`: `useTranslation('core')`. Keys are grouped by area (`users.*`, `workflow.*`…). */
export const CORE_NS = 'core';
registerNamespace(CORE_NS, { vi, en });
