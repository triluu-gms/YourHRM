import { Button, Result } from 'antd';
import { Link, useRouter, useSearch, type ErrorComponentProps } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { ApiProblem } from '@/shared/api';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <Result
      status="404"
      title="404"
      subTitle={t('error.notFound')}
      extra={
        <Link to="/">
          <Button type="primary">{t('nav.home')}</Button>
        </Link>
      }
    />
  );
}

export function ForbiddenPage() {
  const { t } = useTranslation();
  const search = useSearch({ strict: false }) as { permission?: string };
  return (
    <Result
      status="403"
      title="403"
      subTitle={
        <>
          {t('error.forbidden')}
          {search.permission ? <div>{t('error.forbiddenDetail', { permission: search.permission })}</div> : null}
        </>
      }
      extra={
        <Link to="/">
          <Button type="primary">{t('nav.home')}</Button>
        </Link>
      }
    />
  );
}

export function RouteErrorPage({ error, reset }: ErrorComponentProps) {
  const { t } = useTranslation();
  const router = useRouter();
  const problem = error instanceof ApiProblem ? error.problem : undefined;
  const detail = problem
    ? (t(`error.${problem.code}`, { defaultValue: problem.detail ?? '' }) as string)
    : error instanceof Error
      ? error.message
      : String(error);
  return (
    <Result
      status={problem?.status === 404 ? '404' : problem?.status === 403 ? '403' : 'error'}
      title={t('error.title')}
      subTitle={detail || t('error.generic')}
      extra={
        <Button
          type="primary"
          onClick={() => {
            reset();
            void router.invalidate();
          }}
        >
          {t('action.retry')}
        </Button>
      }
    />
  );
}
