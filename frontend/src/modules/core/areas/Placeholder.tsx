import { Result } from 'antd';
import { useTranslation } from 'react-i18next';
import '../i18n';

/** Temporary page for an area whose screens are not built yet. */
export function Placeholder({ area }: { area: string }) {
  const { t } = useTranslation('core');
  return <Result status="info" title={t('placeholder.title')} subTitle={t('placeholder.body', { area })} />;
}
