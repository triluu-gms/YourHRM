import { useMemo, useState } from 'react';
import {
  Alert,
  Button,
  DatePicker,
  Drawer,
  Form,
  Input,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile } from 'antd';
import {
  CloudUploadOutlined,
  EyeOutlined,
  FileTextOutlined,
  PictureOutlined,
  PlusOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import { isProblem, saveBlob, type ISODate, type UUID } from '@/shared/api';
import { PermissionGate } from '@/shared/auth';
import {
  ConfirmButton,
  DataTable,
  DescriptionList,
  FormDrawer,
  PageHeader,
  useFeedback,
} from '@/shared/components';
import { DATE_FORMAT, ISO_DATE, formatDate, formatValidity, today } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { useLegalEntities } from '../employer/queries';
import { useSequences } from '../configuration/queries';
import {
  CORE_MERGE_FIELDS,
  OUTPUT_FORMATS,
  TEMPLATE_PURPOSES,
  placeholderOf,
  publishedVersion,
  type MergeField,
  type OutputFormat,
  type SourceFormat,
  type TemplateDefinition,
  type TemplatePurpose,
  type TemplateVersionView,
  type TemplateView,
} from './api';
import {
  useCreateTemplate,
  useDocumentTemplates,
  usePreviewVersion,
  usePublishVersion,
  useTemplateDefinitions,
  useUploadVersion,
} from './queries';
import { DOCUMENTS_NS } from './i18n';

/** Case-insensitive match on the template code and its Vietnamese / English name. */
export function matchesTemplate(x: TemplateView, q: string): boolean {
  const needle = q.trim().toLowerCase();
  return (
    !needle ||
    x.code.toLowerCase().includes(needle) ||
    x.nameVi.toLowerCase().includes(needle) ||
    (x.nameEn ?? '').toLowerCase().includes(needle)
  );
}

/**
 * Document templates (BR-CORE-102, US-CORE-103/104): the templates this company has per registered definition,
 * their versions, and the upload → preview → publish cycle. Generation picks the legal entity's template, else the
 * company-wide one, and within it the version published for the document's date.
 */
export function DocumentTemplatesPage() {
  const { t } = useTranslation(DOCUMENTS_NS);
  const templates = useDocumentTemplates();
  const definitions = useTemplateDefinitions();
  const legalEntities = useLegalEntities();
  const [q, setQ] = useState('');
  const [purpose, setPurpose] = useState<TemplatePurpose | undefined>();
  const [creating, setCreating] = useState(false);
  const [openId, setOpenId] = useState<UUID | null>(null);

  const all = useMemo(() => templates.data ?? [], [templates.data]);
  const rows = all.filter((x) => matchesTemplate(x, q) && (!purpose || x.purpose === purpose));
  const selected = all.find((x) => x.id === openId) ?? null;
  const onDate = today();

  const entityName = (id: UUID | null | undefined) => {
    if (!id) return t('templates.tenantWide');
    const le = (legalEntities.data ?? []).find((x) => x.id === id);
    return le?.current?.shortName ?? le?.current?.legalNameVi ?? le?.code ?? id;
  };

  return (
    <div>
      <PageHeader
        title={t('templates.title')}
        subTitle={t('templates.subtitle')}
        extra={
          <PermissionGate anyOf={P.DOCUMENT_TEMPLATE_MANAGE}>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              aria-label={t('templates.create')}
              onClick={() => setCreating(true)}
            >
              {t('templates.create')}
            </Button>
          </PermissionGate>
        }
      />

      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          allowClear
          placeholder={t('templates.search')}
          aria-label={t('templates.search')}
          style={{ width: 280 }}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        <Select<TemplatePurpose>
          allowClear
          aria-label={t('templates.field.purpose')}
          placeholder={t('templates.allPurposes')}
          style={{ width: 200 }}
          value={purpose}
          onChange={setPurpose}
          options={TEMPLATE_PURPOSES.map((p) => ({ value: p, label: t(`purpose.${p}`) }))}
        />
      </Space>

      <DataTable<TemplateView>
        data={rows}
        isLoading={templates.isLoading}
        error={templates.error}
        locale={{ emptyText: templates.isLoading ? undefined : t('templates.empty') }}
        columns={[
          {
            title: t('templates.field.code'),
            dataIndex: 'code',
            render: (code: string, x) => (
              <Space direction="vertical" size={0}>
                <Typography.Text style={{ fontFamily: 'monospace' }}>{code}</Typography.Text>
                <Typography.Text type="secondary">{x.nameVi}</Typography.Text>
              </Space>
            ),
          },
          {
            title: t('templates.field.purpose'),
            dataIndex: 'purpose',
            render: (p: TemplatePurpose) => t(`purpose.${p}`),
          },
          {
            title: t('templates.field.scope'),
            dataIndex: 'legalEntityId',
            render: (id: UUID | null) => entityName(id),
          },
          {
            title: t('templates.field.sequence'),
            dataIndex: 'numberingSequenceCode',
            render: (code: string | null) =>
              code ? (
                <Typography.Text style={{ fontFamily: 'monospace' }}>{code}</Typography.Text>
              ) : (
                <Typography.Text type="secondary">{t('templates.noSequence')}</Typography.Text>
              ),
          },
          {
            title: t('templates.field.inForce'),
            key: 'inForce',
            render: (_, x) => <InForceTag template={x} onDate={onDate} />,
          },
          {
            key: 'actions',
            fixed: 'right',
            render: (_, x) => (
              <Button
                size="small"
                icon={<FileTextOutlined />}
                aria-label={t('templates.openLabel', { code: x.code })}
                onClick={() => setOpenId(x.id)}
              />
            ),
          },
        ]}
      />

      <CreateTemplateDrawer
        open={creating}
        onClose={() => setCreating(false)}
        definitions={definitions.data ?? []}
        existing={all}
      />

      {selected ? (
        <TemplateDrawer
          key={selected.id}
          template={selected}
          definition={(definitions.data ?? []).find((d) => d.code === selected.code)}
          scopeName={entityName(selected.legalEntityId)}
          onClose={() => setOpenId(null)}
        />
      ) : null}
    </div>
  );
}

/** The version generation would pick today, or a warning that nothing is published. */
function InForceTag({ template, onDate }: { template: TemplateView; onDate: ISODate }) {
  const { t } = useTranslation(DOCUMENTS_NS);
  const live = publishedVersion(template, onDate);
  return live ? (
    <Tag color="green">
      {t('templates.inForceSince', { no: live.versionNo, from: formatDate(live.validFrom) })}
    </Tag>
  ) : (
    <Tag color="orange">{t('templates.notPublished')}</Tag>
  );
}

// ------------------------------------------------------------------------------------------------ create

function CreateTemplateDrawer({
  open,
  onClose,
  definitions,
  existing,
}: {
  open: boolean;
  onClose: () => void;
  definitions: TemplateDefinition[];
  existing: TemplateView[];
}) {
  const { t } = useTranslation(DOCUMENTS_NS);
  const fb = useFeedback();
  const [form] = Form.useForm<{
    code?: string;
    legalEntityId?: UUID | null;
    numberingSequenceCode?: string | null;
  }>();
  const create = useCreateTemplate();
  const legalEntities = useLegalEntities(undefined, undefined, open);
  const sequences = useSequences();
  const legalEntityId = Form.useWatch('legalEntityId', form);

  /** The same code may exist once per scope, so only codes free in the chosen scope are offered. */
  const taken = new Set(
    existing.filter((x) => (x.legalEntityId ?? null) === (legalEntityId ?? null)).map((x) => x.code),
  );
  const available = definitions.filter((d) => !taken.has(d.code));

  const onSubmit = async () => {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    try {
      await create.mutateAsync({
        code: values.code!,
        legalEntityId: values.legalEntityId ?? null,
        numberingSequenceCode: values.numberingSequenceCode ?? null,
      });
      fb.done(t('templates.created'));
      form.resetFields();
      onClose();
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('templates.createTitle')}
      onSubmit={() => void onSubmit()}
      submitting={create.isPending}
      error={create.error}
      width={520}
    >
      <Form form={form} layout="vertical">
        <Alert type="info" showIcon message={t('templates.createHint')} style={{ marginBottom: 16 }} />
        <Form.Item
          name="legalEntityId"
          label={t('templates.field.legalEntity')}
          extra={t('templates.tenantWide')}
        >
          <Select
            allowClear
            placeholder={t('templates.tenantWide')}
            options={(legalEntities.data ?? []).map((le) => ({
              value: le.id,
              label: le.current?.shortName ?? le.current?.legalNameVi ?? le.code,
            }))}
          />
        </Form.Item>
        <Form.Item
          name="code"
          label={t('templates.field.code')}
          rules={[{ required: true, message: t('templates.field.code') }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            notFoundContent={definitions.length ? t('templates.allTaken') : t('templates.noDefinitions')}
            options={available.map((d) => ({
              value: d.code,
              label: `${d.code} · ${d.nameVi}`,
            }))}
          />
        </Form.Item>
        <Form.Item
          name="numberingSequenceCode"
          label={t('templates.field.sequence')}
          extra={t('templates.sequenceHint')}
        >
          <Select
            allowClear
            placeholder={t('templates.noSequence')}
            options={(sequences.data ?? []).map((s) => ({ value: s.code, label: `${s.code} · ${s.name}` }))}
          />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ detail

function TemplateDrawer({
  template,
  definition,
  scopeName,
  onClose,
}: {
  template: TemplateView;
  definition?: TemplateDefinition;
  scopeName: string;
  onClose: () => void;
}) {
  const { t } = useTranslation(DOCUMENTS_NS);
  const fb = useFeedback();
  const [uploading, setUploading] = useState(false);
  const preview = usePreviewVersion(template.id);
  const publish = usePublishVersion(template.id);

  const runPreview = async (version: TemplateVersionView) => {
    try {
      const blob = await preview.mutateAsync(version.id);
      const name = `${template.code}-v${version.versionNo}.${version.outputFormat === 'DOCX' ? 'docx' : 'pdf'}`;
      const url = URL.createObjectURL(blob);
      const tab = window.open(url, '_blank', 'noopener');
      if (tab) {
        fb.done(t('versions.previewOpened'));
        // the tab owns the blob now; release this handle once it has loaded
        window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
      } else {
        saveBlob(blob, name);
        URL.revokeObjectURL(url);
        fb.done(t('versions.previewBlocked'));
      }
    } catch (e) {
      fb.failed(e);
    }
  };

  const runPublish = async (version: TemplateVersionView) => {
    try {
      await publish.mutateAsync(version.id);
      fb.done(t('versions.published'));
    } catch (e) {
      fb.failed(e);
    }
  };

  return (
    <>
      <Drawer
        open
        onClose={onClose}
        width={760}
        destroyOnHidden
        title={
          <Space>
            <Typography.Text style={{ fontFamily: 'monospace' }}>{template.code}</Typography.Text>
            <Typography.Text type="secondary">{scopeName}</Typography.Text>
          </Space>
        }
      >
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <DescriptionList
            column={2}
            size="small"
            items={[
              { label: t('templates.field.name'), value: template.nameVi },
              { label: t('templates.field.purpose'), value: t(`purpose.${template.purpose}`) },
              { label: t('templates.field.ownerModule'), value: template.ownerModule },
              { label: t('templates.field.documentType'), value: definition?.documentTypeCode },
              { label: t('templates.field.scope'), value: scopeName },
              {
                label: t('templates.field.sequence'),
                value: template.numberingSequenceCode ?? t('templates.noSequence'),
              },
            ]}
          />

          <section aria-label={t('versions.title')}>
            <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }}>
              <Typography.Title level={5} style={{ margin: 0 }}>
                {t('versions.title')}
              </Typography.Title>
              <PermissionGate anyOf={P.DOCUMENT_TEMPLATE_MANAGE}>
                <Button
                  icon={<CloudUploadOutlined />}
                  aria-label={t('versions.upload')}
                  onClick={() => setUploading(true)}
                >
                  {t('versions.upload')}
                </Button>
              </PermissionGate>
            </Space>
            <Table<TemplateVersionView>
              rowKey="id"
              size="small"
              dataSource={template.versions}
              pagination={false}
              locale={{ emptyText: t('versions.none') }}
              columns={[
                { title: t('versions.field.versionNo'), dataIndex: 'versionNo', width: 90 },
                {
                  title: t('versions.field.sourceFormat'),
                  dataIndex: 'sourceFormat',
                  render: (f: SourceFormat) => t(`sourceFormat.${f}`),
                },
                {
                  title: t('versions.field.outputFormat'),
                  dataIndex: 'outputFormat',
                  render: (f: OutputFormat) => t(`outputFormat.${f}`),
                },
                {
                  title: t('versions.field.validity'),
                  key: 'validity',
                  render: (_, v) => formatValidity(v.validFrom, v.validTo, ''),
                },
                {
                  title: t('versions.field.status'),
                  dataIndex: 'status',
                  render: (s: string) => (
                    <Tag color={s === 'PUBLISHED' ? 'green' : s === 'DRAFT' ? 'blue' : undefined}>
                      {t(`versionStatus.${s}`)}
                    </Tag>
                  ),
                },
                {
                  title: t('versions.field.usedFields'),
                  dataIndex: 'mergeFields',
                  render: (fields: string[]) =>
                    fields.length ? (
                      <Tooltip title={fields.join(', ')}>
                        <span>{fields.length}</span>
                      </Tooltip>
                    ) : (
                      <Typography.Text type="secondary">0</Typography.Text>
                    ),
                },
                {
                  key: 'actions',
                  fixed: 'right',
                  render: (_, v) => (
                    <Space size={4}>
                      <Button
                        size="small"
                        icon={<EyeOutlined />}
                        loading={preview.isPending && preview.variables === v.id}
                        aria-label={t('versions.previewLabel', { no: v.versionNo })}
                        onClick={() => void runPreview(v)}
                      />
                      {v.status === 'DRAFT' ? (
                        <PermissionGate anyOf={P.DOCUMENT_TEMPLATE_MANAGE}>
                          <ConfirmButton
                            size="small"
                            type="primary"
                            title={t('versions.publishConfirm')}
                            description={t('versions.publishHint')}
                            aria-label={t('versions.publishLabel', { no: v.versionNo })}
                            loading={publish.isPending && publish.variables === v.id}
                            onConfirm={() => runPublish(v)}
                          >
                            {t('versions.publish')}
                          </ConfirmButton>
                        </PermissionGate>
                      ) : null}
                    </Space>
                  ),
                },
              ]}
            />
          </section>

          <MergeFieldReference definition={definition} />
        </Space>
      </Drawer>

      <UploadVersionDrawer
        open={uploading}
        onClose={() => setUploading(false)}
        template={template}
        onUploaded={() => fb.done(t('versions.uploaded'))}
      />
    </>
  );
}

// ------------------------------------------------------------------------------------------------ upload

function UploadVersionDrawer({
  open,
  onClose,
  template,
  onUploaded,
}: {
  open: boolean;
  onClose: () => void;
  template: TemplateView;
  onUploaded: () => void;
}) {
  const { t } = useTranslation(DOCUMENTS_NS);
  const [form] = Form.useForm<{ file?: UploadFile[]; validFrom?: Dayjs; outputFormat: OutputFormat }>();
  const upload = useUploadVersion(template.id);

  /** `unknown-merge-fields` carries the offending placeholders; they are what the author has to fix. */
  const unknownFields = isProblem(upload.error, 'unknown-merge-fields')
    ? ((upload.error.problem.fields as string[] | undefined) ?? [])
    : [];

  const onSubmit = async () => {
    let values;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const file = values.file?.[0]?.originFileObj as File | undefined;
    if (!file) return;
    try {
      await upload.mutateAsync({
        file,
        validFrom: values.validFrom ? values.validFrom.format(ISO_DATE) : null,
        outputFormat: values.outputFormat,
      });
      onUploaded();
      form.resetFields();
      onClose();
    } catch {
      /* shown by the drawer's ProblemAlert and the unknown-field list */
    }
  };

  return (
    <FormDrawer
      open={open}
      onClose={onClose}
      title={t('versions.uploadTitle')}
      onSubmit={() => void onSubmit()}
      submitting={upload.isPending}
      error={unknownFields.length ? undefined : upload.error}
      width={520}
    >
      {unknownFields.length ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('versions.unknownFields')}
          description={
            <Space size={[4, 4]} wrap>
              {unknownFields.map((f) => (
                <Tag key={f} color="red">
                  <Typography.Text code>{f}</Typography.Text>
                </Tag>
              ))}
            </Space>
          }
        />
      ) : null}
      <Form form={form} layout="vertical" initialValues={{ validFrom: dayjs(), outputFormat: 'PDF' }}>
        <Alert type="info" showIcon message={t('versions.uploadHint')} style={{ marginBottom: 16 }} />
        <Form.Item
          name="file"
          label={t('versions.field.file')}
          valuePropName="fileList"
          getValueFromEvent={(e: { fileList: UploadFile[] }) => e.fileList.slice(-1)}
          rules={[{ required: true, type: 'array', min: 1, message: t('versions.fileRequired') }]}
        >
          <Upload beforeUpload={() => false} maxCount={1} accept=".html,.htm,.docx">
            <Button icon={<UploadOutlined />}>{t('versions.field.file')}</Button>
          </Upload>
        </Form.Item>
        <Form.Item name="validFrom" label={t('versions.field.validFrom')} extra={t('versions.validFromHint')}>
          <DatePicker format={DATE_FORMAT} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="outputFormat"
          label={t('versions.field.outputFormat')}
          extra={t('versions.outputHint')}
        >
          <Radio.Group
            options={OUTPUT_FORMATS.map((f) => ({ value: f, label: t(`outputFormat.${f}`) }))}
            optionType="button"
          />
        </Form.Item>
      </Form>
    </FormDrawer>
  );
}

// ------------------------------------------------------------------------------------------------ merge fields

/** What a template author may write in the file: the definition's own fields plus Core's common ones. */
function MergeFieldReference({ definition }: { definition?: TemplateDefinition }) {
  const { t } = useTranslation(DOCUMENTS_NS);
  const fb = useFeedback();
  const copy = (text: string) => {
    void navigator.clipboard?.writeText(text).then(() => fb.done(t('fields.copied')));
  };

  const group = (title: string, fields: MergeField[]) =>
    fields.length ? (
      <div key={title} style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">{title}</Typography.Text>
        <Table<MergeField>
          rowKey="key"
          size="small"
          dataSource={fields}
          pagination={false}
          showHeader={false}
          columns={[
            {
              dataIndex: 'key',
              width: 260,
              render: (_, f) => (
                <Tag
                  style={{ cursor: 'pointer' }}
                  onClick={() => copy(placeholderOf(f))}
                  title={t('fields.copied')}
                  icon={f.image ? <PictureOutlined /> : undefined}
                >
                  <Typography.Text code>{placeholderOf(f)}</Typography.Text>
                </Tag>
              ),
            },
            {
              dataIndex: 'labelVi',
              render: (labelVi: string, f) => (
                <Space direction="vertical" size={0}>
                  <span>{labelVi}</span>
                  {f.labelEn ? <Typography.Text type="secondary">{f.labelEn}</Typography.Text> : null}
                </Space>
              ),
            },
            {
              dataIndex: 'sample',
              render: (sample: string | null, f) =>
                f.image ? (
                  <Typography.Text type="secondary">{t('fields.image')}</Typography.Text>
                ) : (
                  <Typography.Text type="secondary">{sample}</Typography.Text>
                ),
            },
          ]}
        />
      </div>
    ) : null;

  return (
    <section aria-label={t('fields.title')}>
      <Typography.Title level={5}>{t('fields.title')}</Typography.Title>
      <Typography.Paragraph type="secondary">{t('fields.hint')}</Typography.Paragraph>
      {group(t('fields.fromModule'), definition?.mergeFields ?? [])}
      {group(t('fields.fromCore'), CORE_MERGE_FIELDS)}
    </section>
  );
}
