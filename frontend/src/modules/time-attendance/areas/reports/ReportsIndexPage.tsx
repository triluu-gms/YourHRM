import { Card, Col, Empty, Row, Space, Tag, Typography } from 'antd';
import { FileExcelOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { hasPermission, useMe } from '@/shared/auth';
import { PageHeader } from '@/shared/components';
import { AttendancePermissions as P } from '../../permissions';
import { REPORTS } from './shared';
import { REPORTS_NS } from './i18n';
import './i18n';

/** Catalogue of the T&A reports (BRD 03 §8): one card per report the user may run. */
export function ReportsIndexPage() {
  const { t } = useTranslation(REPORTS_NS);
  const me = useMe();
  const canExport = hasPermission(me, P.REPORT_EXPORT);
  const visible = REPORTS.filter((r) => hasPermission(me, r.permission));

  return (
    <div>
      <PageHeader title={t('index.title')} subTitle={t('index.intro')} />
      {visible.length === 0 ? (
        <Empty description={t('index.none')} />
      ) : (
        <Row gutter={[16, 16]}>
          {visible.map((r) => (
            <Col key={r.key} xs={24} md={12} xl={8}>
              <Link to={`/attendance/reports/${r.key}`} style={{ display: 'block', height: '100%' }}>
                <Card hoverable style={{ height: '100%' }}>
                  <Space direction="vertical" size={4}>
                    <Space>
                      <Tag>{r.code}</Tag>
                      <Typography.Text strong>{t(`catalog.${r.key}.title`)}</Typography.Text>
                    </Space>
                    <Typography.Text type="secondary">{t(`catalog.${r.key}.description`)}</Typography.Text>
                    {r.exportKind && canExport ? (
                      <Typography.Text type="success" style={{ fontSize: 12 }}>
                        <FileExcelOutlined /> {t('index.excel')}
                      </Typography.Text>
                    ) : null}
                  </Space>
                </Card>
              </Link>
            </Col>
          ))}
        </Row>
      )}
    </div>
  );
}
