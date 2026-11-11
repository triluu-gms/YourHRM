import i18n from 'i18next';
import ICU from 'i18next-icu';
import { initReactI18next } from 'react-i18next';
import dayjs from 'dayjs';
import 'dayjs/locale/vi';
import 'dayjs/locale/en';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';
import localizedFormat from 'dayjs/plugin/localizedFormat';
import commonVi from './vi/common.json';
import commonEn from './en/common.json';

dayjs.extend(utc);
dayjs.extend(timezone);
dayjs.extend(localizedFormat);

/** UI languages (NFR-SAAS-013: vi default, en; never mixed). */
export type UiLanguage = 'vi' | 'en';
export const SUPPORTED_LANGUAGES: UiLanguage[] = ['vi', 'en'];
export const DISPLAY_TIME_ZONE = 'Asia/Ho_Chi_Minh';

/** `vi-VN` / `en-US` (user or tenant locale from `/me`) → UI language. */
export function toUiLanguage(locale: string | null | undefined): UiLanguage {
  return locale?.toLowerCase().startsWith('en') ? 'en' : 'vi';
}

void i18n
  .use(ICU)
  .use(initReactI18next)
  .init({
    resources: {
      vi: { common: commonVi },
      en: { common: commonEn },
    },
    lng: 'vi',
    fallbackLng: 'vi',
    supportedLngs: SUPPORTED_LANGUAGES,
    defaultNS: 'common',
    ns: ['common'],
    interpolation: { escapeValue: false },
    returnNull: false,
  });

/** Modules register their namespaces lazily (`addResourceBundle` is idempotent). */
export function registerNamespace(ns: string, bundles: Record<UiLanguage, Record<string, unknown>>) {
  for (const lang of SUPPORTED_LANGUAGES) {
    if (!i18n.hasResourceBundle(lang, ns)) i18n.addResourceBundle(lang, ns, bundles[lang], true, true);
  }
}

/**
 * Modules add keys to the `common` namespace, e.g. their menu labels (`nav.*`, read by the shell with the default
 * namespace). Deep merge; existing shared keys are never overwritten.
 */
export function extendCommon(bundles: Record<UiLanguage, Record<string, unknown>>) {
  for (const lang of SUPPORTED_LANGUAGES) i18n.addResourceBundle(lang, 'common', bundles[lang], true, false);
}

/**
 * Modules add messages for their own problem codes to `common:error.<code>`, which `ProblemAlert` and
 * `useFeedback` look up. Deep merge without overwriting the shared messages.
 */
export function registerProblemMessages(bundles: Record<UiLanguage, Record<string, string>>) {
  for (const lang of SUPPORTED_LANGUAGES) i18n.addResourceBundle(lang, 'common', { error: bundles[lang] }, true, false);
}

export async function changeLanguage(lang: UiLanguage) {
  dayjs.locale(lang);
  document.documentElement.lang = lang;
  await i18n.changeLanguage(lang);
}

export default i18n;
