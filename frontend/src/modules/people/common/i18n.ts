import { extendCommon, registerNamespace } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';
import navVi from '../i18n/vi.json';
import navEn from '../i18n/en.json';

/** Namespace `people`: shared People vocabulary (statuses, field labels, profile tabs). Menu labels go to `common:nav.people.*`. */
export const PEOPLE_NS = 'people';
registerNamespace(PEOPLE_NS, { vi, en });
extendCommon({ vi: navVi, en: navEn });
