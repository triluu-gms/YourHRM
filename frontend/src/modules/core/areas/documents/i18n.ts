import { registerNamespace, registerProblemMessages } from '@/shared/i18n';
import vi from './i18n/vi.json';
import en from './i18n/en.json';

/** Namespace `core-documents`: the document library and the template registry. */
export const DOCUMENTS_NS = 'core-documents';
registerNamespace(DOCUMENTS_NS, { vi, en });
/** Problem codes of `DocumentTemplateController` / `DocumentTemplateService`, shown by `ProblemAlert` and `useFeedback`. */
registerProblemMessages({ vi: vi.errors, en: en.errors });
