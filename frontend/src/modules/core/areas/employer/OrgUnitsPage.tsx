import { useMemo, useState, type ReactNode } from 'react';
import { Button, Card, Checkbox, Col, Empty, Input, Row, Space, Spin, Tag, Tree, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { AsOfPicker, PageHeader, ProblemAlert } from '@/shared/components';
import { today } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import type { OrgUnit } from './api';
import { useOrgTree } from './queries';
import { buildOrgTree, orgUnitLabel, type OrgTreeNode } from './orgTree';
import { OrgUnitForm } from './OrgUnitForm';
import { OrgUnitActions, OrgUnitSummary } from './OrgUnitParts';
import { EMPLOYER_NS } from './i18n';

interface TreeItem {
  key: UUID;
  title: ReactNode;
  children: TreeItem[];
}

/** The org tree as of a date (BR-CORE-012, US-CORE-013): search, closed units, select a unit to act on it. */
export function OrgUnitsPage() {
  const { t, i18n } = useTranslation();
  const { t: te } = useTranslation(EMPLOYER_NS);
  const [asOf, setAsOf] = useState(today);
  const [q, setQ] = useState('');
  const [includeClosed, setIncludeClosed] = useState(false);
  const [selectedId, setSelectedId] = useState<UUID | null>(null);
  const [creatingRoot, setCreatingRoot] = useState(false);
  const query = useOrgTree({ asOf, includeClosed: includeClosed || undefined, q: q || undefined });

  const units = useMemo(() => query.data ?? [], [query.data]);
  const byId = useMemo(() => new Map(units.map((u) => [u.id, u])), [units]);
  const treeData = useMemo(() => {
    const toItem = (n: OrgTreeNode): TreeItem => ({
      key: n.unit.id,
      title: (
        <span>
          {orgUnitLabel(n.unit, i18n.language)}
          {n.unit.typeCode ? (
            <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
              {n.unit.typeCode}
            </Typography.Text>
          ) : null}
          {n.unit.status !== 'ACTIVE' ? (
            <Tag style={{ marginLeft: 6 }}>{t(`status.${n.unit.status}`, { defaultValue: n.unit.status })}</Tag>
          ) : null}
        </span>
      ),
      children: n.children.map(toItem),
    });
    return buildOrgTree(units).map(toItem);
  }, [units, i18n.language, t]);
  const selected: OrgUnit | undefined = selectedId ? byId.get(selectedId) : undefined;

  return (
    <div>
      <PageHeader
        title={te('orgUnit.title')}
        extra={
          <PermissionGate anyOf={P.ORG_UNIT_CREATE}>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreatingRoot(true)}>
              {te('orgUnit.createRoot')}
            </Button>
          </PermissionGate>
        }
      >
        <Space wrap>
          <AsOfPicker value={asOf} onChange={setAsOf} />
          <Input.Search
            allowClear
            aria-label={t('action.search')}
            placeholder={te('orgUnit.search')}
            style={{ width: 280 }}
            onSearch={(v) => setQ(v.trim())}
          />
          <Checkbox checked={includeClosed} onChange={(e) => setIncludeClosed(e.target.checked)}>
            {te('orgUnit.includeClosed')}
          </Checkbox>
        </Space>
      </PageHeader>

      {query.error ? <ProblemAlert error={query.error} style={{ marginBottom: 16 }} /> : null}
      <Row gutter={16}>
        <Col xs={24} lg={10}>
          <Card title={te('orgUnit.units', { count: units.length })} styles={{ body: { minHeight: 200 } }}>
            {query.isLoading ? (
              <Spin />
            ) : treeData.length === 0 ? (
              <Empty description={t('common.empty')} />
            ) : (
              <Tree<TreeItem>
                // Re-mount per data set so every level starts expanded.
                key={`${asOf}|${q}|${includeClosed}|${units.length}`}
                defaultExpandAll
                showLine
                blockNode
                treeData={treeData}
                selectedKeys={selectedId ? [selectedId] : []}
                onSelect={(keys) => setSelectedId((keys[0] as UUID | undefined) ?? null)}
              />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card
            title={selected ? orgUnitLabel(selected, i18n.language) : te('common.details')}
            extra={
              selected ? (
                <Link to="/core/org-units/$orgUnitId" params={{ orgUnitId: selected.id }}>
                  {te('common.open')}
                </Link>
              ) : null
            }
          >
            {selected ? (
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                <Space wrap>
                  <OrgUnitActions unit={selected} onClosed={() => setSelectedId(null)} />
                </Space>
                <OrgUnitSummary unit={selected} parent={selected.parentId ? byId.get(selected.parentId) : null} />
              </Space>
            ) : (
              <Typography.Text type="secondary">{te('orgUnit.selectHint')}</Typography.Text>
            )}
          </Card>
        </Col>
      </Row>

      {creatingRoot ? <OrgUnitForm mode={{ kind: 'create' }} onClose={() => setCreatingRoot(false)} /> : null}
    </div>
  );
}
