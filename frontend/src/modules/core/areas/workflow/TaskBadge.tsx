import { Badge, Tooltip } from 'antd';
import { CheckSquareOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { http } from '@/shared/api';

export const taskCountsQuery = {
  queryKey: ['core', 'workflow', 'tasks', 'counts'] as const,
  queryFn: () => http.get<Record<string, number>>('/core/workflow/tasks/counts'),
};

/** Header widget: number of open approval tasks of the user (US-CORE-059). */
export function TaskBadge() {
  const { t } = useTranslation();
  const counts = useQuery({ ...taskCountsQuery, refetchInterval: 60_000 });
  const total = counts.data ? Object.values(counts.data).reduce((a, b) => a + b, 0) : 0;
  return (
    <Tooltip title={t('header.tasks')}>
      <Link to="/core/workflow/tasks" style={{ color: 'inherit' }}>
        <Badge count={total} size="small" overflowCount={99}>
          <CheckSquareOutlined style={{ fontSize: 18 }} aria-label={t('header.tasks')} />
        </Badge>
      </Link>
    </Tooltip>
  );
}
