import { useState } from 'react';
import { Checkbox, Col, Empty, Input, Row, Space, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { DataTable, StatusTag } from '@/shared/components';
import { localizedName } from '@/shared/format';
import type { Bank, BankBranch } from './api';
import { useBankBranches, useBanks } from './queries';
import { MASTER_DATA_NS } from './i18n';

/** Banks and their branches (BR-CORE-080): the BIN and SWIFT codes the payroll bank files and People's accounts use. */
export function BanksPanel() {
  const { t, i18n } = useTranslation();
  const { t: tm } = useTranslation(MASTER_DATA_NS);
  const [q, setQ] = useState('');
  const [includeInactive, setIncludeInactive] = useState(false);
  const [selected, setSelected] = useState<Bank | null>(null);
  const [branchQ, setBranchQ] = useState('');
  const banks = useBanks(q, includeInactive);
  const branches = useBankBranches(selected?.bankCode, branchQ);

  return (
    <Row gutter={16}>
      <Col xs={24} lg={13}>
        <Space wrap style={{ marginBottom: 12 }}>
          <Input.Search
            allowClear
            aria-label={tm('bank.search')}
            placeholder={tm('bank.search')}
            style={{ width: 240 }}
            onSearch={setQ}
          />
          <Checkbox checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)}>
            {tm('bank.includeInactive')}
          </Checkbox>
        </Space>
        <DataTable<Bank>
          rowKey="bankCode"
          data={banks.data}
          isLoading={banks.isLoading}
          error={banks.error}
          locale={{ emptyText: tm('bank.empty') }}
          onRow={(row) => ({
            onClick: () => {
              setBranchQ('');
              setSelected(row);
            },
            style: { cursor: 'pointer' },
          })}
          rowClassName={(row) => (row.bankCode === selected?.bankCode ? 'ant-table-row-selected' : '')}
          columns={[
            { title: t('field.code'), dataIndex: 'bankCode', render: (c: string) => <code>{c}</code> },
            { title: tm('bank.shortName'), dataIndex: 'shortName' },
            { title: t('field.name'), key: 'name', render: (_, row) => localizedName(row, i18n.language) },
            { title: tm('bank.bin'), dataIndex: 'bin' },
            { title: tm('bank.swift'), dataIndex: 'swiftCode' },
            {
              title: t('field.status'),
              dataIndex: 'active',
              render: (a: boolean) => <StatusTag status={a ? 'ACTIVE' : 'INACTIVE'} />,
            },
          ]}
        />
      </Col>
      <Col xs={24} lg={11}>
        {selected ? (
          <>
            <Typography.Title level={5} style={{ marginTop: 0 }}>
              {tm('bank.branches', { bank: selected.shortName || selected.bankCode })}
            </Typography.Title>
            <Input.Search
              allowClear
              aria-label={tm('bank.searchBranches')}
              placeholder={tm('bank.searchBranches')}
              style={{ width: 240, marginBottom: 12 }}
              onSearch={setBranchQ}
            />
            <DataTable<BankBranch>
              rowKey="branchCode"
              data={branches.data}
              isLoading={branches.isLoading}
              error={branches.error}
              columns={[
                { title: tm('bank.branchCode'), dataIndex: 'branchCode', render: (c: string) => <code>{c}</code> },
                { title: t('field.name'), dataIndex: 'nameVi' },
                { title: tm('bank.province'), dataIndex: 'provinceCode' },
                {
                  title: t('field.status'),
                  dataIndex: 'active',
                  render: (a: boolean) => <StatusTag status={a ? 'ACTIVE' : 'INACTIVE'} />,
                },
              ]}
            />
          </>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={tm('bank.pickBank')} />
        )}
      </Col>
    </Row>
  );
}
