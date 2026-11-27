import { extendCommon, registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';
import navVi from '../i18n/vi.json';
import navEn from '../i18n/en.json';
import errorsVi from './i18n/errors.vi.json';
import errorsEn from './i18n/errors.en.json';

/** Namespace `att`: shared T&A vocabulary (pickers, field labels). Menu labels go to `common:nav.att.*`. */
export const ATT_NS = 'att';
registerNamespace(ATT_NS, { vi, en });
extendCommon({ vi: navVi, en: navEn });

/**
 * Messages for every T&A problem code (`common:error.<code>`), registered once for the whole module so the wording
 * never depends on which area loaded first. Areas keep their own `errors.*` keys only for form-field hints.
 */
registerProblemMessages({ vi: errorsVi, en: errorsEn });
