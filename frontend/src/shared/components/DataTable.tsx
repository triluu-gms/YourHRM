import { Table, type TableProps } from 'antd';
import { useTranslation } from 'react-i18next';
import type { Page } from '@/shared/api';
import { ProblemAlert } from './ProblemAlert';

export interface DataTableProps<T> extends Omit<TableProps<T>, 'dataSource' | 'pagination' | 'loading'> {
  /** Server page (offset) or a plain array for unpaged lists. */
  data: Page<T> | T[] | undefined;
  isLoading?: boolean;
  error?: unknown;
  /** Current 0-based page and size, with the setter (server-side paging). */
  paging?: { page: number; size: number; onChange: (page: number, size: number) => void; hasMore?: boolean };
}

/** Ant Table wired to the Core page shape (0-based `page`, `size`, `total`) with problem rendering. */
export function DataTable<T extends object>({ data, isLoading, error, paging, rowKey = 'id', ...rest }: DataTableProps<T>) {
  const { t } = useTranslation();
  const items = Array.isArray(data) ? data : (data?.items ?? []);
  const total = Array.isArray(data) ? data.length : data?.total;
  return (
    <>
      {error ? <ProblemAlert error={error} style={{ marginBottom: 12 }} /> : null}
      <Table<T>
        size="middle"
        rowKey={rowKey}
        dataSource={items}
        loading={isLoading}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: t('common.empty') }}
        pagination={
          paging
            ? {
                current: paging.page + 1,
                pageSize: paging.size,
                total: total ?? (paging.hasMore ? (paging.page + 2) * paging.size : (paging.page + 1) * paging.size),
                showSizeChanger: true,
                showTotal: total !== undefined ? (n) => t('common.total', { count: n }) : undefined,
                onChange: (p, s) => paging.onChange(p - 1, s),
              }
            : false
        }
        {...rest}
      />
    </>
  );
}
