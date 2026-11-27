import { useState } from 'react';
import { Alert, Button, Card, DatePicker, Form, Input, Modal, Select, Space, Statistic, Typography } from 'antd';
import { StopOutlined, SyncOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DataTable, PageHeader, ProblemAlert, useFeedback, usePaging } from '@/shared/components';
import { DATE_FORMAT, formatDate, formatDateTime, ISO_DATE } from '@/shared/format';
import { LegalEntitySelect } from '../../common';
import { AttendancePermissions as P } from '../../permissions';
import { MAX_RANGE_DAYS, QUARANTINE_STATUSES, type PunchRef, type PunchRow, type RematchResult } from './api';
import { useIgnorePunches, useQuarantine, useQuarantineCounts, useRematch } from './queries';
import { DeviceSelect, NS, PunchStatusTag, shortId, useDeviceCodes } from './components';

const localTime = (ts: string) => dayjs(ts).format(`${DATE_FORMAT} HH:mm:ss`);

/** Quarantined punches (BR-ATT-025, US-ATT-015): counts, list, re-match, ignore with a reason (bulk selection). */
export function QuarantinePage() {
  const { t } = useTranslation(NS);
  const [range, setRange] = useState<[Dayjs, Dayjs]>(() => [dayjs().subtract(30, 'day'), dayjs()]);
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [status, setStatus] = useState<string | undefined>();
  const [deviceId, setDeviceId] = useState<UUID | undefined>();
  const [terminalCode, setTerminalCode] = useState('');
  const [enrolId, setEnrolId] = useState('');
  const [selected, setSelected] = useState<PunchRef[]>([]);
  const [ignoring, setIgnoring] = useState(false);
  const [rematching, setRematching] = useState(false);
  const paging = usePaging(100);
  const canResolve = usePermission(P.PUNCH_RESOLVE);
  const devices = useDeviceCodes();

  const from = range[0].format(ISO_DATE);
  const to = range[1].format(ISO_DATE);
  const tooLong = range[1].diff(range[0], 'day') >= MAX_RANGE_DAYS;
  const q = useQuarantine(
    {
      from,
      to,
      status,
      deviceId,
      terminalCode: terminalCode.trim() || undefined,
      enrolId: enrolId.trim() || undefined,
      limit: paging.size,
      offset: paging.page * paging.size,
    },
    !tooLong,
  );
  const counts = useQuarantineCounts({ legalEntityId, from, to }, !tooLong);

  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    paging.reset();
    setSelected([]);
  };

  return (
    <div>
      <PageHeader
        title={t('quarantine.title')}
        subTitle={t('quarantine.subtitle')}
        extra={
          <PermissionGate anyOf={P.PUNCH_RESOLVE}>
            <Button icon={<SyncOutlined />} disabled={tooLong} onClick={() => setRematching(true)}>
              {t('quarantine.rematch')}
            </Button>
            <Button danger icon={<StopOutlined />} disabled={selected.length === 0} onClick={() => setIgnoring(true)}>
              {t('quarantine.ignoreSelected', { count: selected.length })}
            </Button>
          </PermissionGate>
        }
      />

      <Space wrap style={{ marginBottom: 12 }}>
        <DatePicker.RangePicker
          aria-label={t('field.range')}
          format={DATE_FORMAT}
          allowClear={false}
          value={range}
          onChange={(v) => v?.[0] && v[1] && reset(setRange)([v[0], v[1]])}
        />
        <Select
          aria-label={t('field.validationStatus')}
          allowClear
          placeholder={t('quarantine.allStatuses')}
          style={{ width: 240 }}
          value={status}
          onChange={reset(setStatus)}
          options={QUARANTINE_STATUSES.map((s) => ({ value: s, label: t(`punchStatus.${s}`) }))}
        />
        <DeviceSelect aria-label={t('field.device')} placeholder={t('quarantine.allDevices')} style={{ width: 220 }} value={deviceId} onChange={reset(setDeviceId)} />
        <Input.Search aria-label={t('field.terminalCode')} placeholder={t('field.terminalCode')} allowClear style={{ width: 200 }} onSearch={reset(setTerminalCode)} />
        <Input.Search aria-label={t('field.enrolId')} placeholder={t('field.enrolId')} allowClear style={{ width: 180 }} onSearch={reset(setEnrolId)} />
      </Space>

      {tooLong ? <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('quarantine.rangeTooLong', { days: MAX_RANGE_DAYS })} /> : null}

      <Card size="small" title={t('quarantine.counts')} style={{ marginBottom: 12 }} extra={
        <LegalEntitySelect aria-label={t('quarantine.countsLegalEntity')} placeholder={t('quarantine.countsLegalEntity')} style={{ width: 260 }} value={legalEntityId} onChange={setLegalEntityId} />
      }>
        {counts.error ? <ProblemAlert error={counts.error} /> : null}
        <Space size="large" wrap>
          {QUARANTINE_STATUSES.map((s) => (
            <Statistic key={s} title={t(`punchStatus.${s}`)} value={counts.data?.[s] ?? 0} loading={counts.isLoading} />
          ))}
        </Space>
      </Card>

      <DataTable<PunchRow>
        data={q.data}
        isLoading={q.isLoading}
        error={q.error}
        locale={{ emptyText: t('quarantine.empty') }}
        paging={{ ...paging, onChange: (p, s) => { paging.onChange(p, s); setSelected([]); }, hasMore: (q.data?.length ?? 0) >= paging.size }}
        rowSelection={
          canResolve
            ? {
                selectedRowKeys: selected.map((r) => r.id),
                onChange: (_keys, rows) => setSelected(rows.map((r) => ({ id: r.id, workDateHint: r.workDateHint }))),
              }
            : undefined
        }
        columns={[
          { title: t('field.workDateHint'), dataIndex: 'workDateHint', render: (d: string) => formatDate(d) },
          { title: t('field.localTs'), dataIndex: 'localTs', render: localTime },
          { title: t('field.punchAt'), dataIndex: 'punchAt', render: (v: string) => formatDateTime(v) },
          {
            title: t('field.device'),
            key: 'device',
            render: (_, p) => (p.deviceId ? (devices.get(p.deviceId) ?? shortId(p.deviceId)) : null),
          },
          { title: t('field.terminalCode'), dataIndex: 'terminalCode' },
          { title: t('field.enrolId'), dataIndex: 'enrolId', render: (v: string | null) => (v ? <code>{v}</code> : null) },
          { title: t('field.direction'), dataIndex: 'direction', render: (v: string | null) => (v ? t(`direction.${v}`, { defaultValue: v }) : null) },
          { title: t('field.validationStatus'), dataIndex: 'validationStatus', render: (s: string) => <PunchStatusTag status={s} /> },
          {
            title: t('field.importJob'),
            key: 'job',
            render: (_, p) => (p.importJobId ? `${shortId(p.importJobId)}${p.importRowNo != null ? ` / ${p.importRowNo}` : ''}` : null),
          },
        ]}
      />

      {ignoring ? (
        <IgnoreModal
          punches={selected}
          onClose={(done) => {
            setIgnoring(false);
            if (done) setSelected([]);
          }}
        />
      ) : null}
      {rematching ? (
        <RematchModal from={from} to={to} deviceId={deviceId} enrolId={enrolId.trim() || undefined} onClose={() => setRematching(false)} />
      ) : null}
    </div>
  );
}

function IgnoreModal({ punches, onClose }: { punches: PunchRef[]; onClose: (done: boolean) => void }) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const [reason, setReason] = useState('');
  const ignore = useIgnorePunches();
  const fb = useFeedback();
  const submit = async () => {
    try {
      const r = await ignore.mutateAsync({ punches, reason: reason.trim() });
      fb.done(t('quarantine.ignoreDone', { count: r.ignored }));
      onClose(true);
    } catch {
      // shown in the modal
    }
  };
  return (
    <Modal
      open
      title={t('quarantine.ignoreTitle', { count: punches.length })}
      onCancel={() => onClose(false)}
      onOk={() => void submit()}
      okText={t('quarantine.ignore')}
      cancelText={tc('action.cancel')}
      okButtonProps={{ danger: true, disabled: !reason.trim(), loading: ignore.isPending }}
      destroyOnHidden
    >
      {ignore.error ? <ProblemAlert error={ignore.error} style={{ marginBottom: 12 }} /> : null}
      <Typography.Paragraph type="secondary">{t('quarantine.ignoreHelp')}</Typography.Paragraph>
      <Form layout="vertical">
        <Form.Item label={t('field.reason')} required>
          <Input.TextArea aria-label={t('field.reason')} rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function RematchModal({
  from,
  to,
  deviceId,
  enrolId,
  onClose,
}: {
  from: string;
  to: string;
  deviceId?: UUID;
  enrolId?: string;
  onClose: () => void;
}) {
  const { t } = useTranslation(NS);
  const { t: tc } = useTranslation();
  const rematch = useRematch();
  const [result, setResult] = useState<RematchResult | null>(null);
  const submit = async () => {
    try {
      setResult(await rematch.mutateAsync({ from, to, deviceId: deviceId ?? null, enrolId: enrolId ?? null }));
    } catch {
      // shown in the modal
    }
  };
  return (
    <Modal
      open
      title={t('quarantine.rematchTitle')}
      onCancel={onClose}
      onOk={result ? onClose : () => void submit()}
      okText={result ? tc('action.close') : t('quarantine.rematch')}
      cancelButtonProps={{ style: result ? { display: 'none' } : undefined }}
      cancelText={tc('action.cancel')}
      okButtonProps={{ loading: rematch.isPending }}
      destroyOnHidden
    >
      {rematch.error ? <ProblemAlert error={rematch.error} style={{ marginBottom: 12 }} /> : null}
      <Typography.Paragraph>{t('quarantine.rematchHelp')}</Typography.Paragraph>
      <Typography.Paragraph type="secondary">
        {formatDate(from)} – {formatDate(to)}
        {enrolId ? ` · ${enrolId}` : ''}
      </Typography.Paragraph>
      {result ? (
        <Alert
          type="success"
          showIcon
          message={t('quarantine.rematchDone', { examined: result.examined, matched: result.matched, still: result.stillQuarantined })}
        />
      ) : null}
    </Modal>
  );
}
