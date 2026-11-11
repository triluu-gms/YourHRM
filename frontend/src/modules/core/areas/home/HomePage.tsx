import { Card, Col, Row, Statistic, Typography } from 'antd';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { http } from '@/shared/api';
import { hasPermission, useMe } from '@/shared/auth';
import { filterMenu } from '@/shared/navigation';
import { coreMenu } from '../../menu';
import '../../i18n';

export function HomePage() {
  const { t } = useTranslation();
  const { t: tc } = useTranslation('core');
  const me = useMe();
  const tasks = useQuery({
    queryKey: ['core', 'workflow', 'tasks', 'counts'],
    queryFn: () => http.get<Record<string, number>>('/core/workflow/tasks/counts'),
  });
  const unread = useQuery({
    queryKey: ['core', 'notifications', 'unread-count'],
    queryFn: () => http.get<{ unread: number }>('/core/notifications/unread-count'),
  });
  const taskTotal = tasks.data ? Object.values(tasks.data).reduce((a, b) => a + b, 0) : undefined;
  const links = filterMenu(coreMenu, (...codes) => hasPermission(me, ...codes)).flatMap((e) => e.children ?? [e]);

  return (
    <div>
      <Typography.Title level={3}>{tc('home.welcome', { name: me.user.login })}</Typography.Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Link to="/core/workflow/tasks">
            <Card hoverable>
              <Statistic title={tc('home.tasksPending')} value={taskTotal ?? '…'} loading={tasks.isLoading} />
            </Card>
          </Link>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Link to="/core/notifications">
            <Card hoverable>
              <Statistic title={tc('home.unreadNotifications')} value={unread.data?.unread ?? '…'} loading={unread.isLoading} />
            </Card>
          </Link>
        </Col>
      </Row>
      <Typography.Title level={5} style={{ marginTop: 24 }}>
        {tc('home.quickLinks')}
      </Typography.Title>
      <Row gutter={[12, 12]}>
        {links.map((l) => (
          <Col xs={12} sm={8} md={6} lg={4} key={l.key}>
            <Link to={l.path!}>
              <Card size="small" hoverable>
                {t(l.labelKey)}
              </Card>
            </Link>
          </Col>
        ))}
      </Row>
    </div>
  );
}
