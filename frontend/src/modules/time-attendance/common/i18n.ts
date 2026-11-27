import { extendCommon, registerNamespace } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';
import navVi from '../i18n/vi.json';
import navEn from '../i18n/en.json';

/** Namespace `att`: shared T&A vocabulary (pickers, field labels). Menu labels go to `common:nav.att.*`. */
export const ATT_NS = 'att';
registerNamespace(ATT_NS, { vi, en });
extendCommon({ vi: navVi, en: navEn });
