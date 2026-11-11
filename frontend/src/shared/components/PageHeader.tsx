import type { ReactNode } from 'react';
import { Breadcrumb, Space, Typography } from 'antd';
import { Link } from '@tanstack/react-router';

export interface Crumb {
  label: ReactNode;
  to?: string;
}

/** Page title row: breadcrumb, title, optional subtitle and the primary actions on the right. */
export function PageHeader({
  title,
  subTitle,
  breadcrumb,
  extra,
  children,
}: {
  title: ReactNode;
  subTitle?: ReactNode;
  breadcrumb?: Crumb[];
  extra?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div style={{ marginBottom: 16 }}>
      {breadcrumb && breadcrumb.length > 0 ? (
        <Breadcrumb
          style={{ marginBottom: 8 }}
          items={breadcrumb.map((c) => ({ title: c.to ? <Link to={c.to}>{c.label}</Link> : c.label }))}
        />
      ) : null}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {title}
          </Typography.Title>
          {subTitle ? <Typography.Text type="secondary">{subTitle}</Typography.Text> : null}
        </div>
        {extra ? <Space wrap>{extra}</Space> : null}
      </div>
      {children ? <div style={{ marginTop: 12 }}>{children}</div> : null}
    </div>
  );
}
