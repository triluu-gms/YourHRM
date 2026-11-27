import { useState } from 'react';
import { Alert, App, Button, Card, Col, DatePicker, Row, Space, Table, Tag, Typography } from 'antd';
import { BellOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/shared/auth';
import { DescriptionList, PageHeader, ProblemAlert } from '@/shared/components';
import { DATE_FORMAT, formatDate } from '@/shared/format';
import { AttendancePermissions as P } from '../../permissions';
import type { NightlySummary, ReminderSummary, StepResult } from './api';
import { useRunNightly, useRunReminders } from './queries';
import { toIso } from './shared';
import { REPORTS_NS } from './i18n';
import './i18n';

/** Manual runs of T&A's nightly job and daily reminders (operations, catch-up after an outage). */
export function JobsPage() {
  const { t } = useTranslation(REPORTS_NS);
  return (
    <div>
      <PageHeader title={t('jobs.title')} subTitle={t('jobs.intro')} />
      <Row gutter={[16, 16]}>
        <PermissionGate anyOf={P.JOB_RUN}>
          <Col xs={24} xl={12}>
            <NightlyCard />
          </Col>
        </PermissionGate>
        <PermissionGate anyOf={P.REMINDER_RUN}>
          <Col xs={24} xl={12}>
            <RemindersCard />
          </Col>
        </PermissionGate>
      </Row>
    </div>
  );
}

function useConfirm() {
  const { modal } = App.useApp();
  const { t } = useTranslation();
  return (title: string, content: string, onOk: () => void) =>
    modal.confirm({ title, content, okText: t('action.confirm'), cancelText: t('action.cancel'), onOk });
}

function NightlyCard() {
  const { t } = useTranslation(REPORTS_NS);
  const confirm = useConfirm();
  const run = useRunNightly();
  const [day, setDay] = useState<Dayjs | null>(null);
  const [result, setResult] = useState<NightlySummary | null>(null);
  const failed = result?.steps.some((s) => s.failures.length > 0) ?? false;

  return (
    <Card title={t('jobs.nightly.title')}>
      <Typography.Paragraph type="secondary">{t('jobs.nightly.description')}</Typography.Paragraph>
      <Space wrap style={{ marginBottom: 12 }}>
        <DatePicker
          aria-label={t('jobs.today')}
          placeholder={t('jobs.todayPlaceholder')}
          format={DATE_FORMAT}
          value={day}
          onChange={setDay}
        />
        <Button
          type="primary"
          icon={<PlayCircleOutlined />}
          loading={run.isPending}
          onClick={() =>
            confirm(t('jobs.nightly.confirmTitle'), t('jobs.nightly.confirmText'), () => {
              setResult(null);
              run.mutate(toIso(day), { onSuccess: setResult });
            })
          }
        >
          {t('jobs.nightly.run')}
        </Button>
      </Space>
      {run.error ? <ProblemAlert error={run.error} style={{ marginBottom: 12 }} /> : null}
      {result ? (
        <>
          <Alert
            type={failed ? 'warning' : 'success'}
            showIcon
            style={{ marginBottom: 12 }}
            message={t(failed ? 'jobs.nightly.doneWithFailures' : 'jobs.nightly.done', { day: formatDate(result.day) })}
          />
          <Table<StepResult>
            size="small"
            rowKey={(s, i) => `${s.step}:${i}`}
            pagination={false}
            dataSource={result.steps}
            columns={[
              { title: t('jobs.step'), dataIndex: 'step', render: (s: string) => t(`jobs.steps.${s}`, { defaultValue: s }) },
              { title: t('jobs.count'), dataIndex: 'count', align: 'right' },
              {
                title: t('jobs.failures'),
                dataIndex: 'failures',
                render: (f: string[]) =>
                  f.length === 0 ? (
                    <Tag color="green">{t('jobs.ok')}</Tag>
                  ) : (
                    <ul style={{ margin: 0, paddingLeft: 16 }}>
                      {f.map((x, i) => (
                        <li key={i}>
                          <Typography.Text type="danger" style={{ fontSize: 12 }}>
                            {x}
                          </Typography.Text>
                        </li>
                      ))}
                    </ul>
                  ),
              },
            ]}
          />
        </>
      ) : null}
    </Card>
  );
}

function RemindersCard() {
  const { t } = useTranslation(REPORTS_NS);
  const confirm = useConfirm();
  const run = useRunReminders();
  const [day, setDay] = useState<Dayjs | null>(null);
  const [result, setResult] = useState<ReminderSummary | null>(null);

  return (
    <Card title={t('jobs.reminders.title')}>
      <Typography.Paragraph type="secondary">{t('jobs.reminders.description')}</Typography.Paragraph>
      <Space wrap style={{ marginBottom: 12 }}>
        <DatePicker
          aria-label={t('jobs.today')}
          placeholder={t('jobs.todayPlaceholder')}
          format={DATE_FORMAT}
          value={day}
          onChange={setDay}
        />
        <Button
          type="primary"
          icon={<BellOutlined />}
          loading={run.isPending}
          onClick={() =>
            confirm(t('jobs.reminders.confirmTitle'), t('jobs.reminders.confirmText'), () => {
              setResult(null);
              run.mutate(toIso(day), { onSuccess: setResult });
            })
          }
        >
          {t('jobs.reminders.run')}
        </Button>
      </Space>
      {run.error ? <ProblemAlert error={run.error} style={{ marginBottom: 12 }} /> : null}
      {result ? (
        <>
          <Alert
            type={result.failures > 0 ? 'warning' : 'success'}
            showIcon
            style={{ marginBottom: 12 }}
            message={t('jobs.reminders.done', { day: formatDate(result.day) })}
          />
          <DescriptionList
            column={1}
            items={[
              { label: t('jobs.reminders.missingPunch'), value: result.missingPunch },
              { label: t('jobs.reminders.carryExpiry'), value: result.carryExpiry },
              { label: t('jobs.reminders.closeDue'), value: result.closeDue },
              { label: t('jobs.failures'), value: result.failures },
            ]}
          />
        </>
      ) : null}
    </Card>
  );
}
