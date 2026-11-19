import { useMemo, useState } from 'react';
import { Alert, Button, DatePicker, Drawer, Form, Input, InputNumber, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { EyeOutlined, LockOutlined, PaperClipOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { DataTable, DescriptionList, PageHeader, ProblemAlert } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE } from '@/shared/format';
import { CHANNELS, LOCALES, type TemplateItem } from './api';
import { useNotificationTemplates, useTemplatePreview } from './queries';
import { CategoryTag, channelLabel } from './widgets';
import { NOTIFICATIONS_NS } from './i18n';

const templateKey = (t: Pick<TemplateItem, 'eventCode' | 'channel' | 'locale'>) => `${t.eventCode}|${t.channel}|${t.locale}`;

/** Case-insensitive match on event code and subject. */
export function matchesTemplate(t: TemplateItem, q: string): boolean {
  const needle = q.trim().toLowerCase();
  return !needle || t.eventCode.toLowerCase().includes(needle) || (t.subject ?? '').toLowerCase().includes(needle);
}

/**
 * Template registry (US-CORE-070): the vendor's texts per event × channel × locale, read-only in Phase 1 (tenant
 * overrides are P2), with a preview that renders a template from sample values.
 */
export function TemplatesPage() {
  const { t } = useTranslation(NOTIFICATIONS_NS);
  const query = useNotificationTemplates();
  const [q, setQ] = useState('');
  const [category, setCategory] = useState<string | undefined>();
  const [channel, setChannel] = useState<string | undefined>();
  const [selected, setSelected] = useState<TemplateItem | null>(null);

  const all = useMemo(() => query.data ?? [], [query.data]);
  const categories = useMemo(() => [...new Set(all.map((x) => x.category))].sort(), [all]);
  const rows = all.filter(
    (x) => matchesTemplate(x, q) && (!category || x.category === category) && (!channel || x.channel === channel),
  );

  return (
    <div>
      <PageHeader title={t('templates.title')} subTitle={t('templates.subtitle')} />

      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          allowClear
          placeholder={t('templates.search')}
          aria-label={t('templates.search')}
          style={{ width: 280 }}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        <Select<string>
          allowClear
          aria-label={t('templates.field.category')}
          placeholder={t('templates.allCategories')}
          style={{ width: 180 }}
          value={category}
          onChange={setCategory}
          options={categories.map((c) => ({ value: c, label: t(`category.${c}`, { defaultValue: c }) }))}
        />
        <Select<string>
          allowClear
          aria-label={t('templates.field.channel')}
          placeholder={t('templates.allChannels')}
          style={{ width: 180 }}
          value={channel}
          onChange={setChannel}
          options={CHANNELS.map((c) => ({ value: c, label: channelLabel(t, c) }))}
        />
      </Space>

      <DataTable<TemplateItem>
        rowKey={templateKey}
        data={rows}
        isLoading={query.isLoading}
        error={query.error}
        columns={[
          {
            title: t('templates.field.eventCode'),
            dataIndex: 'eventCode',
            render: (code: string) => <Typography.Text style={{ fontFamily: 'monospace' }}>{code}</Typography.Text>,
          },
          {
            title: t('templates.field.category'),
            dataIndex: 'category',
            render: (c: string, x) => (
              <Space size={4}>
                <CategoryTag category={c} />
                {x.mandatory ? (
                  <Tooltip title={t('templates.mandatoryHint')}>
                    <LockOutlined aria-label={t('templates.mandatory')} />
                  </Tooltip>
                ) : null}
              </Space>
            ),
          },
          { title: t('templates.field.channel'), dataIndex: 'channel', render: (c: string) => channelLabel(t, c) },
          { title: t('templates.field.locale'), dataIndex: 'locale', render: (l: string) => <Tag>{l}</Tag> },
          {
            title: t('templates.field.subject'),
            dataIndex: 'subject',
            render: (s: string | null, x) => (
              <Space size={4}>
                <span>{s}</span>
                {x.attachmentAllowed ? <PaperClipOutlined title={t('templates.attachmentAllowed')} /> : null}
              </Space>
            ),
          },
          {
            key: 'actions',
            fixed: 'right',
            render: (_, x) => (
              <Button
                size="small"
                icon={<EyeOutlined />}
                aria-label={t('templates.viewLabel', { event: x.eventCode, channel: x.channel, locale: x.locale })}
                onClick={() => setSelected(x)}
              />
            ),
          },
        ]}
      />

      <Drawer
        open={selected !== null}
        onClose={() => setSelected(null)}
        width={640}
        destroyOnHidden
        title={selected ? `${selected.eventCode} · ${channelLabel(t, selected.channel)} · ${selected.locale}` : null}
      >
        {selected ? <TemplateDetail key={templateKey(selected)} template={selected} /> : null}
      </Drawer>
    </div>
  );
}

// ------------------------------------------------------------------------------------------------ detail + preview

function TemplateDetail({ template }: { template: TemplateItem }) {
  const { t } = useTranslation(NOTIFICATIONS_NS);
  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <DescriptionList
        column={1}
        size="small"
        items={[
          { label: t('templates.field.category'), value: <CategoryTag category={template.category} /> },
          {
            label: t('templates.mandatory'),
            value: template.mandatory ? <Tag icon={<LockOutlined />}>{t('templates.mandatory')}</Tag> : null,
            hideEmpty: true,
          },
          { label: t('templates.field.bodyFormat'), value: template.bodyFormat },
          {
            label: t('templates.attachmentAllowed'),
            value: template.attachmentAllowed ? <PaperClipOutlined /> : null,
            hideEmpty: true,
          },
          { label: t('templates.field.subject'), value: template.subject },
          {
            label: t('templates.field.body'),
            value: (
              <Typography.Paragraph code style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {template.body}
              </Typography.Paragraph>
            ),
          },
          {
            label: t('templates.field.placeholders'),
            value: template.placeholders.length ? (
              <Space size={[4, 4]} wrap>
                {template.placeholders.map((p) => (
                  <Tag key={p.name}>
                    <Typography.Text code>{`{{${p.name}}}`}</Typography.Text> {t(`templates.kind.${p.kind}`)}
                  </Tag>
                ))}
              </Space>
            ) : (
              <Typography.Text type="secondary">{t('templates.noPlaceholders')}</Typography.Text>
            ),
          },
        ]}
      />
      <PreviewPanel template={template} />
    </Space>
  );
}

/** Sample value to start from, by placeholder kind. */
function sampleFor(kind: string, name: string): unknown {
  switch (kind) {
    case 'DATE':
      return dayjs();
    case 'NUMBER':
      return 1;
    case 'LINK':
      return '/';
    default:
      return name;
  }
}

function PreviewPanel({ template }: { template: TemplateItem }) {
  const { t } = useTranslation(NOTIFICATIONS_NS);
  const [form] = Form.useForm<{ locale: string; deepLink?: string; sample: Record<string, unknown> }>();
  const preview = useTemplatePreview();

  const run = async () => {
    const v = form.getFieldsValue(true) as { locale: string; deepLink?: string; sample?: Record<string, unknown> };
    const sample: Record<string, unknown> = {};
    for (const p of template.placeholders) {
      const raw = v.sample?.[p.name];
      if (raw === undefined || raw === null || raw === '') continue;
      sample[p.name] = dayjs.isDayjs(raw) ? raw.format(ISO_DATE) : raw;
    }
    preview.mutate({
      eventCode: template.eventCode,
      channel: template.channel,
      locale: v.locale,
      sample,
      deepLink: v.deepLink?.trim() || undefined,
    });
  };

  const result = preview.data;
  return (
    <section aria-label={t('templates.preview.title')}>
      <Typography.Title level={5}>{t('templates.preview.title')}</Typography.Title>
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          locale: template.locale,
          sample: Object.fromEntries(template.placeholders.map((p) => [p.name, sampleFor(p.kind, p.name)])),
        }}
        onFinish={() => void run()}
      >
        <Form.Item name="locale" label={t('templates.field.locale')}>
          <Select options={LOCALES.map((l) => ({ value: l, label: l }))} style={{ width: 160 }} />
        </Form.Item>
        {template.placeholders.map((p) => (
          <Form.Item key={p.name} name={['sample', p.name]} label={`{{${p.name}}}`}>
            {p.kind === 'DATE' ? (
              <DatePicker format={DATE_FORMAT} />
            ) : p.kind === 'NUMBER' ? (
              <InputNumber style={{ width: 200 }} />
            ) : (
              <Input />
            )}
          </Form.Item>
        ))}
        <Form.Item name="deepLink" label={t('templates.preview.deepLink')} extra={t('templates.preview.deepLinkHint')}>
          <Input placeholder="/core/tasks" />
        </Form.Item>
        <Button type="primary" htmlType="submit" icon={<EyeOutlined />} loading={preview.isPending}>
          {t('templates.preview.run')}
        </Button>
      </Form>

      {preview.error ? <ProblemAlert error={preview.error} style={{ marginTop: 12 }} /> : null}
      {result ? (
        <Alert
          style={{ marginTop: 12 }}
          type="info"
          data-testid="template-preview"
          message={result.subject ?? t('templates.preview.result')}
          description={
            result.bodyFormat === 'HTML' ? (
              <iframe
                title={t('templates.preview.result')}
                sandbox=""
                srcDoc={result.body}
                style={{ width: '100%', minHeight: 240, border: 0, background: '#fff' }}
              />
            ) : (
              <div style={{ whiteSpace: 'pre-wrap' }}>{result.body}</div>
            )
          }
        />
      ) : null}
    </section>
  );
}
