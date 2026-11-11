import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { App as AntApp, ConfigProvider } from 'antd';
import viVN from 'antd/locale/vi_VN';
import enUS from 'antd/locale/en_US';
import { useTranslation } from 'react-i18next';
import { yhrmTheme } from './theme';
import '@/shared/i18n';
import { changeLanguage, toUiLanguage, type UiLanguage } from '@/shared/i18n';

export function AppProviders({ queryClient, children }: { queryClient: QueryClient; children: ReactNode }) {
  const { i18n } = useTranslation();
  const [lang, setLang] = useState<UiLanguage>(toUiLanguage(i18n.language));
  useEffect(() => {
    const handler = (l: string) => setLang(toUiLanguage(l));
    i18n.on('languageChanged', handler);
    return () => i18n.off('languageChanged', handler);
  }, [i18n]);
  const antLocale = useMemo(() => (lang === 'en' ? enUS : viVN), [lang]);

  return (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider theme={yhrmTheme} locale={antLocale} form={{ requiredMark: 'optional' }}>
        <AntApp>{children}</AntApp>
      </ConfigProvider>
      {import.meta.env.DEV ? <ReactQueryDevtools initialIsOpen={false} buttonPosition="bottom-left" /> : null}
    </QueryClientProvider>
  );
}

export { changeLanguage };
