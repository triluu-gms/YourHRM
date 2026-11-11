import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { bffLogin, bffSession, loginMode, redirectToIdpLogin } from '@/shared/auth';

/** BFF login (ADR-0014): with the real hrm-bff the browser is redirected; in dev a form posts to /bff/login. */
export function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const search = useSearch({ strict: false }) as { redirect?: string; reason?: string };
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const returnTo = search.redirect && search.redirect.startsWith('/') ? search.redirect : '/';

  useEffect(() => {
    if (loginMode === 'redirect') {
      redirectToIdpLogin(returnTo);
      return;
    }
    // Already signed in (e.g. back button): skip the form unless the API refused the session.
    if (!search.reason) {
      void bffSession().then((s) => {
        if (s.authenticated) void navigate({ to: returnTo, replace: true });
      });
    }
  }, [navigate, returnTo, search.reason]);

  const onFinish = async (values: { username: string; password: string }) => {
    setSubmitting(true);
    setError(null);
    try {
      await bffLogin(values.username, values.password);
      await queryClient.resetQueries();
      void navigate({ to: returnTo, replace: true });
    } catch (e) {
      const code = (e as { code?: string }).code ?? 'generic';
      setError(t(`error.${code}`, { defaultValue: (e as Error).message }));
    } finally {
      setSubmitting(false);
    }
  };

  if (loginMode === 'redirect') {
    return <div style={{ padding: 48, textAlign: 'center' }}>{t('login.redirecting')}</div>;
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        background: 'linear-gradient(135deg, #0d1b2a 0%, #1f6feb 100%)',
        padding: 16,
      }}
    >
      <Card style={{ width: 'min(400px, 100%)' }}>
        <Typography.Title level={3} style={{ marginTop: 0 }}>
          {t('login.title')}
        </Typography.Title>
        {search.reason && !error ? (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message={t(`error.${search.reason}`, { defaultValue: t('error.unauthenticated') })}
          />
        ) : null}
        {error ? <Alert type="error" showIcon style={{ marginBottom: 16 }} message={error} /> : null}
        <Form layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item name="username" label={t('login.username')} rules={[{ required: true }]}>
            <Input autoComplete="username" autoFocus />
          </Form.Item>
          <Form.Item name="password" label={t('login.password')} rules={[{ required: true }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={submitting}>
            {t('login.submit')}
          </Button>
        </Form>
        {import.meta.env.DEV ? (
          <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0, fontSize: 12 }}>
            {t('login.devHint')}
          </Typography.Paragraph>
        ) : null}
      </Card>
    </div>
  );
}
