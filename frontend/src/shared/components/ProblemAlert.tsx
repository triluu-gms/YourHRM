import { Alert, type AlertProps } from 'antd';
import { useTranslation } from 'react-i18next';
import { ApiProblem } from '@/shared/api';

/** Shows an API problem (RFC 9457) translated by its `code`, with field errors when present. */
export function ProblemAlert({ error, ...rest }: { error: unknown } & Omit<AlertProps, 'message' | 'type'>) {
  const { t } = useTranslation();
  if (!error) return null;
  if (error instanceof ApiProblem) {
    const { code, detail, errors } = error.problem;
    const message = t(`error.${code}`, { defaultValue: detail ?? t('error.generic') });
    return (
      <Alert
        type={error.status >= 500 ? 'error' : 'warning'}
        showIcon
        message={message}
        description={
          errors && errors.length > 0 ? (
            <ul style={{ margin: 0, paddingLeft: 16 }}>
              {errors.map((e, i) => (
                <li key={i}>
                  <b>{e.field}</b>: {e.message}
                </li>
              ))}
            </ul>
          ) : detail && message !== detail ? (
            detail
          ) : undefined
        }
        {...rest}
      />
    );
  }
  const message = error instanceof Error ? error.message : String(error);
  return <Alert type="error" showIcon message={message || t('error.generic')} {...rest} />;
}
