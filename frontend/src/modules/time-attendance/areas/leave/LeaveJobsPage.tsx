import { useState } from 'react';
import { Alert, Card, Col, DatePicker, Descriptions, Form, InputNumber, Row, Space, Tag, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { ConfirmButton, DataTable, PageHeader, ProblemAlert } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { LegalEntitySelect } from '../../common';
import type { RunSummary } from './api';
import { useLeaveRun, type LeaveRun } from './queries';
import { EmploymentRef, NS, toIso } from './components';

/**
 * Leave runs (BR-ATT-058/060): monthly accrual, year-end carry-over and carry expiry, tenant-wide or for one legal
 * entity. The nightly job runs them too; running again is idempotent (ACCRUE once per month, CARRY once per year).
 */
export function LeaveJobsPage() {
  const { t } = useTranslation(NS);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [month, setMonth] = useState<Dayjs>(dayjs().subtract(1, 'month').startOf('month'));
  const [year, setYear] = useState<number>(dayjs().year() - 1);
  const [asOf, setAsOf] = useState<Dayjs | null>(null);
  const run = useLeaveRun();
  const [last, setLast] = useState<{ run: LeaveRun; summary: RunSummary } | null>(null);
  const [failed, setFailed] = useState<unknown>(null);

  const go = async (r: LeaveRun) => {
    setFailed(null);
    try {
      setLast({ run: r, summary: await run.mutateAsync(r) });
    } catch (e) {
      setFailed(e);
    }
  };
  const pending = (kind: LeaveRun['kind']) => run.isPending && run.variables?.kind === kind;

  return (
    <div>
      <PageHeader title={t('jobs.title')} subTitle={t('jobs.intro')}>
        <Space>
          <Typography.Text>{t('jobs.scope')}</Typography.Text>
          <LegalEntitySelect
            aria-label={t('jobs.scope')}
            placeholder={t('common.allLegalEntities')}
            style={{ width: 300 }}
            value={legalEntityId}
            onChange={setLegalEntityId}
          />
        </Space>
      </PageHeader>
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={8}>
          <Card title={t('jobs.accrual.title')}>
            <Typography.Paragraph type="secondary">{t('jobs.accrual.hint')}</Typography.Paragraph>
            <Form layout="vertical">
              <Form.Item label={t('jobs.accrual.month')}>
                <DatePicker
                  aria-label={t('jobs.accrual.month')}
                  picker="month"
                  format="MM/YYYY"
                  allowClear={false}
                  value={month}
                  onChange={(d) => d && setMonth(d)}
                />
              </Form.Item>
            </Form>
            <ConfirmButton
              type="primary"
              loading={pending('accrual')}
              title={t('jobs.accrual.confirm', { month: month.format('MM/YYYY') })}
              onConfirm={() => go({ kind: 'accrual', legalEntityId, month: month.format('YYYY-MM') })}
            >
              {t('jobs.run')}
            </ConfirmButton>
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card title={t('jobs.yearEnd.title')}>
            <Typography.Paragraph type="secondary">{t('jobs.yearEnd.hint')}</Typography.Paragraph>
            <Form layout="vertical">
              <Form.Item label={t('jobs.yearEnd.year')}>
                <InputNumber
                  aria-label={t('jobs.yearEnd.year')}
                  min={2000}
                  max={2100}
                  value={year}
                  onChange={(v) => typeof v === 'number' && setYear(v)}
                />
              </Form.Item>
            </Form>
            <ConfirmButton
              type="primary"
              loading={pending('yearEnd')}
              title={t('jobs.yearEnd.confirm', { year })}
              onConfirm={() => go({ kind: 'yearEnd', legalEntityId, year })}
            >
              {t('jobs.run')}
            </ConfirmButton>
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card title={t('jobs.expiry.title')}>
            <Typography.Paragraph type="secondary">{t('jobs.expiry.hint')}</Typography.Paragraph>
            <Form layout="vertical">
              <Form.Item label={t('jobs.expiry.asOf')} extra={t('jobs.expiry.asOfHint')}>
                <DatePicker aria-label={t('jobs.expiry.asOf')} format={DATE_FORMAT} value={asOf} onChange={setAsOf} />
              </Form.Item>
            </Form>
            <ConfirmButton
              type="primary"
              loading={pending('carryExpiry')}
              title={t('jobs.expiry.confirm', { date: asOf ? formatDate(toIso(asOf)) : t('jobs.expiry.today') })}
              onConfirm={() => go({ kind: 'carryExpiry', legalEntityId, asOf: toIso(asOf) })}
            >
              {t('jobs.run')}
            </ConfirmButton>
          </Card>
        </Col>
      </Row>
      {failed ? <ProblemAlert error={failed} style={{ marginTop: 16 }} /> : null}
      {last ? <RunResult summary={last.summary} /> : null}
    </div>
  );
}

function RunResult({ summary }: { summary: RunSummary }) {
  const { t } = useTranslation(NS);
  const [kind, ...arg] = summary.run.split(' ');
  const failures = Object.entries(summary.failures ?? {}).map(([employmentId, message]) => ({ employmentId, message }));
  return (
    <Card title={t('jobs.result.title')} style={{ marginTop: 16 }} data-testid="run-result">
      <Alert
        type={failures.length ? 'warning' : 'success'}
        showIcon
        style={{ marginBottom: 12 }}
        message={t('jobs.result.summary', {
          run: t(`jobs.result.run.${kind}`, { defaultValue: summary.run, arg: arg.join(' ') }),
          employments: summary.employments,
          written: summary.written,
          skipped: summary.skipped,
          failed: failures.length,
        })}
      />
      <Descriptions size="small" bordered column={{ xs: 1, md: 4 }}>
        <Descriptions.Item label={t('jobs.result.employments')}>{summary.employments}</Descriptions.Item>
        <Descriptions.Item label={t('jobs.result.written')}>{summary.written}</Descriptions.Item>
        <Descriptions.Item label={t('jobs.result.skipped')}>{summary.skipped}</Descriptions.Item>
        <Descriptions.Item label={t('jobs.result.failed')}>{failures.length}</Descriptions.Item>
      </Descriptions>
      {summary.flagged?.length ? (
        <div style={{ marginTop: 12 }}>
          <Typography.Text strong>
            <Tag color="orange">{summary.flagged.length}</Tag>
            {t('jobs.result.flagged')}
          </Typography.Text>
          <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
            {summary.flagged.map((f) => (
              <li key={f}>{f}</li>
            ))}
          </ul>
        </div>
      ) : null}
      {failures.length ? (
        <DataTable
          style={{ marginTop: 12 }}
          rowKey="employmentId"
          data={failures}
          columns={[
            { title: t('field.employee'), dataIndex: 'employmentId', render: (id: string) => <EmploymentRef id={id} /> },
            { title: t('jobs.result.message'), dataIndex: 'message' },
          ]}
        />
      ) : null}
    </Card>
  );
}
