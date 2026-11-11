import { useState } from 'react';
import { Button, Dropdown, type MenuProps } from 'antd';
import { DownOutlined, ExportOutlined, LogoutOutlined, StopOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { usePermission } from '@/shared/auth';
import type { ProfileTabProps } from '../../common';
import { PeoplePermissions as P } from '../../permissions';
import { NS } from './parts';
import { StartSeparationDrawer } from './StartSeparationDrawer';
import { AnonymiseModal, PrivacyExportModal } from './PrivacyDialogs';

type Dialog = 'separation' | 'export' | 'anonymise' | null;

/**
 * Employee-profile header actions of the separation / privacy area: start a separation (`hr.separation.create`, only
 * for an active / suspended employment) and the DSR export / anonymisation (`hr.dsr.execute`). One action shows as a
 * button, several as a dropdown; nothing renders without the permissions.
 */
export function ProfileActions({ employmentId, personId, profile }: ProfileTabProps) {
  const { t } = useTranslation(NS);
  const canSeparate = usePermission(P.SEPARATION_CREATE);
  const canDsr = usePermission(P.DSR_EXECUTE);
  const [dialog, setDialog] = useState<Dialog>(null);

  const status = profile.employment.status;
  const separable = canSeparate && (status === 'ACTIVE' || status === 'SUSPENDED');
  const dsr = canDsr && !profile.person.anonymised;

  const items: NonNullable<MenuProps['items']> = [];
  if (separable) items.push({ key: 'separation', icon: <LogoutOutlined />, label: t('start.action') });
  if (dsr) {
    if (items.length) items.push({ type: 'divider' });
    items.push({ key: 'export', icon: <ExportOutlined />, label: t('privacy.export') });
    items.push({ key: 'anonymise', icon: <StopOutlined />, label: t('privacy.anonymise'), danger: true });
  }
  if (items.length === 0) return null;

  const trigger =
    items.length === 1 && separable ? (
      <Button icon={<LogoutOutlined />} onClick={() => setDialog('separation')}>
        {t('start.action')}
      </Button>
    ) : (
      <Dropdown menu={{ items, onClick: ({ key }) => setDialog(key as Dialog) }} trigger={['click']}>
        <Button>
          {t('more')} <DownOutlined />
        </Button>
      </Dropdown>
    );

  return (
    <>
      {trigger}
      {separable ? (
        <StartSeparationDrawer
          open={dialog === 'separation'}
          onClose={() => setDialog(null)}
          employmentId={employmentId}
          legalEntityId={profile.employment.legalEntityId}
        />
      ) : null}
      {dsr ? (
        <>
          <PrivacyExportModal open={dialog === 'export'} onClose={() => setDialog(null)} personId={personId} />
          <AnonymiseModal open={dialog === 'anonymise'} onClose={() => setDialog(null)} personId={personId} />
        </>
      ) : null}
    </>
  );
}
