import type { ReactNode } from 'react';
import { Descriptions, type DescriptionsProps } from 'antd';

export interface DescriptionItem {
  label: ReactNode;
  value: ReactNode;
  span?: number;
  /** Hide the row when the value is empty. */
  hideEmpty?: boolean;
}

/** Read-only key/value block for detail pages; empty values render as "—". */
export function DescriptionList({ items, ...rest }: { items: DescriptionItem[] } & Omit<DescriptionsProps, 'items'>) {
  const visible = items.filter((i) => !(i.hideEmpty && (i.value === null || i.value === undefined || i.value === '')));
  return (
    <Descriptions
      size="small"
      bordered
      column={{ xs: 1, sm: 1, md: 2, lg: 2, xl: 3 }}
      items={visible.map((i, idx) => ({
        key: idx,
        label: i.label,
        span: i.span,
        children: i.value === null || i.value === undefined || i.value === '' ? '—' : i.value,
      }))}
      {...rest}
    />
  );
}
