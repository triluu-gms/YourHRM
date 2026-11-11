import { App } from 'antd';
import { useTranslation } from 'react-i18next';
import { ApiProblem } from '@/shared/api';

/** Toasts for mutation outcomes, translating problem codes (`error.<code>`). */
export function useFeedback() {
  const { message } = App.useApp();
  const { t } = useTranslation();
  return {
    saved: (text?: string) => message.success(text ?? t('common.saved')),
    created: () => message.success(t('common.created')),
    deleted: () => message.success(t('common.deleted')),
    done: (text?: string) => message.success(text ?? t('common.done')),
    failed: (error: unknown) => {
      if (error instanceof ApiProblem) {
        message.error(t(`error.${error.code}`, { defaultValue: error.problem.detail ?? t('error.generic') }));
      } else {
        message.error(error instanceof Error && error.message ? error.message : t('error.generic'));
      }
    },
  };
}
