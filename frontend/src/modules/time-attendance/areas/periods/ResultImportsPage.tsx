import { useState } from 'react';
import { Alert, Card, Empty, Input, Skeleton, Space } from 'antd';
import { Link } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import { ConfirmButton, DataTable, DescriptionList, PageHeader, ProblemAlert, useFeedback } from '@/shared/components';
import { formatDate } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import type { PublishedPeriod } from './api';
import { useImportJob, usePublishImport } from './queries';
import { AreaStatusTag, NS } from './components';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * `/attendance/result-imports?jobId=…`: the T&A side of an `ATT_RESULT_IMPORT` job (US-ATT-019) — staged and
 * published lines, the periods it published, and a repeat of the publish once Core has committed the job.
 */
export function ResultImportsPage({ jobId, onJobChange }: { jobId?: UUID; onJobChange: (jobId: UUID | undefined) => void }) {
  const { t } = useTranslation(NS);
  const fb = useFeedback();
  const [input, setInput] = useState(jobId ?? '');
  const [invalid, setInvalid] = useState(false);
  const q = useImportJob(jobId);
  const publish = usePublishImport();

  const onSearch = (value: string) => {
    const v = value.trim();
    if (!v) {
      setInvalid(false);
      onJobChange(undefined);
      return;
    }
    if (!UUID_RE.test(v)) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    onJobChange(v);
  };

  const onPublish = async () => {
    if (!jobId) return;
    try {
      await publish.mutateAsync(jobId);
      fb.done(t('imports.publishedDone'));
    } catch (e) {
      fb.failed(e);
    }
  };

  const job = q.data;

  return (
    <div>
      <PageHeader title={t('imports.title')} subTitle={t('imports.subTitle')} />
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input.Search
            style={{ maxWidth: 480 }}
            aria-label={t('imports.jobId')}
            placeholder={t('imports.jobId')}
            enterButton={t('imports.find')}
            value={input}
            status={invalid ? 'error' : undefined}
            onChange={(e) => setInput(e.target.value)}
            onSearch={onSearch}
          />
          {invalid ? <Alert type="error" showIcon message={t('imports.invalidJob')} /> : null}
        </Space>
      </Card>

      {!jobId ? (
        <Empty description={t('imports.enterJob')} />
      ) : q.isLoading ? (
        <Skeleton active />
      ) : q.error ? (
        <ProblemAlert error={q.error} />
      ) : job ? (
        <Card
          size="small"
          title={<AreaStatusTag status={job.status} />}
          extra={
            <PermissionGate anyOf={P.RESULT_IMPORT}>
              {job.status === 'STAGED' ? (
                <ConfirmButton type="primary" title={t('imports.confirmPublish')} loading={publish.isPending} onConfirm={onPublish}>
                  {t('imports.publish')}
                </ConfirmButton>
              ) : null}
            </PermissionGate>
          }
        >
          {publish.error ? <ProblemAlert error={publish.error} style={{ marginBottom: 12 }} /> : null}
          <DescriptionList
            style={{ marginBottom: 16 }}
            items={[
              { label: t('imports.jobId'), value: job.importJobId },
              { label: t('imports.staged'), value: job.stagedLines },
              { label: t('imports.published'), value: job.publishedLines },
            ]}
          />
          <DataTable<PublishedPeriod>
            rowKey="resultId"
            data={job.periods}
            title={() => t('imports.periods')}
            columns={[
              {
                title: t('imports.periodStart'),
                dataIndex: 'periodStart',
                render: (d: string, p: PublishedPeriod) => (
                  <Link to="/attendance/periods/$periodId" params={{ periodId: p.periodId }}>
                    {formatDate(d)}
                  </Link>
                ),
              },
              {
                title: t('field.version'),
                dataIndex: 'version',
                render: (v: number, p: PublishedPeriod) => (
                  <Link to="/attendance/attendance-results/$resultId" params={{ resultId: p.resultId }}>
                    v{v}
                  </Link>
                ),
              },
              { title: t('imports.lines'), dataIndex: 'lines' },
            ]}
          />
        </Card>
      ) : null}
    </div>
  );
}
