import { Suspense, useMemo, useState } from 'react';
import { Avatar, Dropdown, Grid, Layout, Menu, Space, Spin, Typography } from 'antd';
import {
  ApartmentOutlined,
  AuditOutlined,
  BankOutlined,
  BellOutlined,
  BarChartOutlined,
  CheckSquareOutlined,
  FileTextOutlined,
  GlobalOutlined,
  IdcardOutlined,
  ImportOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  SolutionOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Link, Outlet, useLocation, useNavigate } from '@tanstack/react-router';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { ItemType } from 'antd/es/menu/interface';
import { bffLogout, hasPermission, useMe } from '@/shared/auth';
import { changeLanguage, toUiLanguage, type UiLanguage } from '@/shared/i18n';
import { filterMenu, useShellRegistry, type MenuEntry } from '@/shared/navigation';
import { ErrorBoundary } from '@/shared/components/ErrorBoundary';

const ICONS: Record<string, React.ReactNode> = {
  bank: <BankOutlined />,
  apartment: <ApartmentOutlined />,
  team: <TeamOutlined />,
  safety: <SafetyCertificateOutlined />,
  check: <CheckSquareOutlined />,
  bell: <BellOutlined />,
  file: <FileTextOutlined />,
  import: <ImportOutlined />,
  setting: <SettingOutlined />,
  audit: <AuditOutlined />,
  global: <GlobalOutlined />,
  user: <UserOutlined />,
  idcard: <IdcardOutlined />,
  solution: <SolutionOutlined />,
  chart: <BarChartOutlined />,
};

function toItems(entries: MenuEntry[], t: (k: string) => string): ItemType[] {
  return entries.map((e) => ({
    key: e.path ?? e.key,
    icon: e.icon ? ICONS[e.icon] : undefined,
    label: e.path ? <Link to={e.path}>{t(e.labelKey)}</Link> : t(e.labelKey),
    children: e.children ? toItems(e.children, t) : undefined,
  }));
}

/** Admin web shell: sider menu filtered by permissions, header with tenant, widgets, language and user. */
export function AdminLayout() {
  const { t, i18n } = useTranslation();
  const me = useMe();
  const registry = useShellRegistry();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const location = useLocation();
  const screens = Grid.useBreakpoint();
  const [collapsed, setCollapsed] = useState(false);

  const items = useMemo(
    () => toItems(filterMenu(registry.menu, (...codes) => hasPermission(me, ...codes)), t),
    [registry.menu, me, t],
  );
  const selectedKeys = useMemo(() => {
    // Longest registered path that prefixes the current one.
    const paths: string[] = [];
    const walk = (es: MenuEntry[]) => es.forEach((e) => (e.path ? paths.push(e.path) : e.children && walk(e.children)));
    walk(registry.menu);
    const match = paths
      .filter((p) => location.pathname === p || location.pathname.startsWith(p + '/'))
      .sort((a, b) => b.length - a.length)[0];
    return match ? [match] : [];
  }, [registry.menu, location.pathname]);

  const lang = toUiLanguage(i18n.language);
  const onLanguage = (next: UiLanguage) => {
    void changeLanguage(next);
  };
  const onLogout = async () => {
    await bffLogout();
    queryClient.clear();
    void navigate({ to: '/login', replace: true });
  };

  const isMobile = !screens.lg;

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider
        theme="dark"
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        breakpoint="lg"
        collapsedWidth={isMobile ? 0 : 64}
        width={240}
        trigger={null}
        style={{ position: 'sticky', top: 0, height: '100vh', overflow: 'auto' }}
      >
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            padding: '0 16px',
            color: '#fff',
            fontWeight: 700,
            fontSize: 18,
            letterSpacing: 0.5,
          }}
        >
          <Link to={registry.homePath} style={{ color: 'inherit' }}>
            {collapsed ? 'Y' : t('app.name')}
          </Link>
        </div>
        <Menu theme="dark" mode="inline" items={items} selectedKeys={selectedKeys} />
      </Layout.Sider>
      <Layout>
        <Layout.Header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid #d0d7de',
            position: 'sticky',
            top: 0,
            zIndex: 10,
          }}
        >
          <Space>
            <span
              role="button"
              aria-label="toggle menu"
              style={{ fontSize: 18, cursor: 'pointer' }}
              onClick={() => setCollapsed((c) => !c)}
            >
              {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            </span>
            <Typography.Text strong ellipsis style={{ maxWidth: 280 }}>
              {me.tenant.name ?? me.tenant.slug ?? ''}
            </Typography.Text>
          </Space>
          <Space size="middle">
            {registry.headerWidgets.map((Widget, i) => (
              <ErrorBoundary key={i} compact>
                <Widget />
              </ErrorBoundary>
            ))}
            <Dropdown
              menu={{
                selectedKeys: [lang],
                items: [
                  { key: 'vi', label: t('common.vietnamese'), onClick: () => onLanguage('vi') },
                  { key: 'en', label: t('common.english'), onClick: () => onLanguage('en') },
                ],
              }}
            >
              <span style={{ cursor: 'pointer' }} aria-label={t('common.language')}>
                <GlobalOutlined /> {lang.toUpperCase()}
              </span>
            </Dropdown>
            <Dropdown
              menu={{
                items: [
                  { key: 'profile', icon: <UserOutlined />, label: <Link to="/core/me">{t('nav.profile')}</Link> },
                  { type: 'divider' },
                  { key: 'logout', icon: <LogoutOutlined />, label: t('action.signOut'), onClick: () => void onLogout() },
                ],
              }}
            >
              <Space style={{ cursor: 'pointer' }}>
                <Avatar size="small" icon={<UserOutlined />} />
                {!isMobile ? <span>{me.user.login}</span> : null}
              </Space>
            </Dropdown>
          </Space>
        </Layout.Header>
        <Layout.Content style={{ padding: isMobile ? 12 : 24 }}>
          <ErrorBoundary>
            <Suspense fallback={<Spin style={{ display: 'block', marginTop: 80 }} />}>
              <Outlet />
            </Suspense>
          </ErrorBoundary>
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
