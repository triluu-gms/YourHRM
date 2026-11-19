import { useState } from 'react';
import { Alert, Button, DatePicker, Divider, Drawer, Form, Popconfirm, Radio, Skeleton, Space, Table, Typography } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { UUID } from '@/shared/api';
import { PermissionGate, usePermission } from '@/shared/auth';
import { DescriptionList, ProblemAlert, useFeedback } from '@/shared/components';
import { DATE_FORMAT, ISO_DATE, formatDate, formatValidity } from '@/shared/format';
import { CorePermissions as P } from '../../permissions';
import { LegalEntitySelect } from '../employer/widgets';
import {
  SETTING_LEVELS,
  type ResolveParams,
  type SetSettingRequest,
  type Setting,
  type SettingJson,
  type SettingLevel,
  type SettingValue,
} from './api';
import {
  fromEditorValue,
  isMasked,
  isOpenFrom,
  isValidJson,
  settingDescription,
  settingValueText,
  toEditorValue,
} from './format';
import { useRemoveSettingValue, useResolveSetting, useSetSetting, useSetting } from './queries';
import { LevelTag, LocationSelect, SettingValueInput, SettingValueView, useScopeNames } from './widgets';
import { CONFIGURATION_NS } from './i18n';

/** One setting: definition, stored values per level, "set a value" and "which value applies?". */
export function SettingDrawer({ settingKey, onClose }: { settingKey: string; onClose: () => void }) {
  const { t, i18n } = useTranslation(CONFIGURATION_NS);
  const { t: tc } = useTranslation();
  const query = useSetting(settingKey);
  const canUpdate = usePermission(P.SETTING_UPDATE);
  const s = query.data;

  return (
    <Drawer open onClose={onClose} width={780} destroyOnHidden title={t('settings.drawerTitle', { key: settingKey })}>
      {query.error ? <ProblemAlert error={query.error} /> : null}
      {query.isLoading ? <Skeleton active /> : null}
      {s ? (
        <>
          <Typography.Paragraph strong>{settingDescription(s, i18n.language)}</Typography.Paragraph>
          <DescriptionList
            column={2}
            items={[
              { label: t('settings.field.key'), value: <Typography.Text code>{s.key}</Typography.Text> },
              { label: t('common.module'), value: t(`module.${s.ownerModule}`, { defaultValue: s.ownerModule }) },
              { label: t('settings.field.type'), value: t(`type.${s.valueType}`) },
              { label: t('settings.field.default'), value: <SettingValueView type={s.valueType} value={s.defaultValue} /> },
              {
                label: t('settings.field.levels'),
                value: (
                  <Space size={2} wrap>
                    {s.allowedLevels.map((l) => (
                      <LevelTag key={l} level={l} />
                    ))}
                  </Space>
                ),
              },
              { label: t('settings.field.effectiveDated'), value: tc(s.effectiveDated ? 'common.yes' : 'common.no') },
              ...(s.valueType === 'ENUM'
                ? [{ label: t('settings.field.allowedValues'), value: s.allowedValues.join(', '), span: 2 }]
                : []),
            ]}
          />
          {s.sensitive ? <Alert type="info" showIcon style={{ marginTop: 12 }} message={t('settings.sensitiveHint')} /> : null}

          <Divider orientation="left">{t('settings.values.title')}</Divider>
          <StoredValues setting={s} canUpdate={canUpdate} />

          <PermissionGate anyOf={P.SETTING_UPDATE}>
            <section aria-label={t('settings.set.title')}>
              <Divider orientation="left">{t('settings.set.title')}</Divider>
              <SetValueForm setting={s} />
            </section>
          </PermissionGate>

          {s.allowedLevels.length > 1 || s.effectiveDated ? (
            <section aria-label={t('settings.resolve.title')}>
              <Divider orientation="left">{t('settings.resolve.title')}</Divider>
              <ResolvePanel setting={s} />
            </section>
          ) : null}
        </>
      ) : null}
    </Drawer>
  );
}

// ------------------------------------------------------------------------------------------------ stored values

function StoredValues({ setting, canUpdate }: { setting: Setting; canUpdate: boolean }) {
  const { t } = useTranslation(CONFIGURATION_NS);
  const { t: tc } = useTranslation();
  const names = useScopeNames();
  const fb = useFeedback();
  const remove = useRemoveSettingValue(setting.key);

  const scope = (v: SettingValue) => (
    <Space size={4} wrap>
      <LevelTag level={v.level} />
      {v.level === 'LEGAL_ENTITY' ? names.legalEntity(v.legalEntityId) : null}
      {v.level === 'LOCATION' ? names.location(v.locationId) : null}
    </Space>
  );

  return (
    <Table<SettingValue>
      size="small"
      rowKey="id"
      pagination={false}
      dataSource={setting.values}
      locale={{ emptyText: t('settings.values.empty') }}
      columns={[
        { title: t('settings.field.scope'), key: 'scope', render: (_: unknown, v) => scope(v) },
        {
          title: t('settings.field.value'),
          key: 'value',
          render: (_: unknown, v) => <SettingValueView type={setting.valueType} value={v.value} />,
        },
        {
          title: t('settings.field.validity'),
          key: 'validity',
          render: (_: unknown, v) =>
            setting.effectiveDated && !isOpenFrom(v.validFrom)
              ? formatValidity(v.validFrom, v.validTo, tc('common.openEnded'))
              : t('common.always'),
        },
        ...(canUpdate
          ? [
              {
                title: tc('field.actions'),
                key: 'actions',
                render: (_: unknown, v: SettingValue) => (
                  <Popconfirm
                    title={t('settings.values.removeConfirm')}
                    okButtonProps={{ danger: true }}
                    onConfirm={() =>
                      remove.mutateAsync(v.id).then(
                        () => void fb.done(t('settings.values.removed')),
                        (e: unknown) => void fb.failed(e),
                      )
                    }
                  >
                    <Button size="small" danger icon={<DeleteOutlined />} aria-label={t('settings.values.remove')} />
                  </Popconfirm>
                ),
              },
            ]
          : []),
      ]}
    />
  );
}

// ------------------------------------------------------------------------------------------------ set a value

interface SetValues {
  level: SettingLevel;
  legalEntityId?: UUID;
  locationId?: UUID;
  value?: unknown;
  validFrom?: Dayjs | null;
}

/** Plain-text value for a toast. */
function useValueText() {
  const { t } = useTranslation(CONFIGURATION_NS);
  return (setting: Setting, value: SettingJson) =>
    setting.valueType === 'BOOL' && !isMasked(value)
      ? t(value === true ? 'common.true' : 'common.false')
      : settingValueText(setting.valueType, value);
}

function SetValueForm({ setting }: { setting: Setting }) {
  const { t } = useTranslation(CONFIGURATION_NS);
  const { t: tc } = useTranslation();
  const fb = useFeedback();
  const valueText = useValueText();
  const [form] = Form.useForm<SetValues>();
  const set = useSetSetting(setting.key);
  const level = Form.useWatch('level', form);
  const levels = SETTING_LEVELS.filter((l) => setting.allowedLevels.includes(l));
  const type = setting.valueType;
  const initialValue = toEditorValue(type, setting.tenantValue) ?? (type === 'BOOL' ? false : undefined);

  const submit = async () => {
    let v: SetValues;
    try {
      v = await form.validateFields();
    } catch {
      return;
    }
    const body: SetSettingRequest = {
      level: v.level,
      legalEntityId: v.level === 'LEGAL_ENTITY' ? v.legalEntityId : null,
      locationId: v.level === 'LOCATION' ? v.locationId : null,
      value: fromEditorValue(type, v.value),
      validFrom: setting.effectiveDated && v.validFrom ? v.validFrom.format(ISO_DATE) : null,
    };
    try {
      const resolved = await set.mutateAsync(body);
      fb.done(t('settings.set.done', { value: valueText(setting, resolved.value), source: t(`level.${resolved.source}`) }));
    } catch {
      // shown by the ProblemAlert above the form
    }
  };

  // Custom validators: a typeless `required` rule validates as a string and would refuse numbers and dates.
  const valueRules =
    type === 'BOOL'
      ? []
      : [
          {
            validator: (_: unknown, val: unknown) => {
              if (type === 'JSON') {
                return isValidJson(val) ? Promise.resolve() : Promise.reject(new Error(t('settings.set.jsonInvalid')));
              }
              const empty = val === undefined || val === null || (typeof val === 'string' && !val.trim());
              return empty ? Promise.reject(new Error(t('settings.set.valueRequired'))) : Promise.resolve();
            },
          },
        ];

  return (
    <>
      {set.error ? <ProblemAlert error={set.error} style={{ marginBottom: 12 }} /> : null}
      <Form<SetValues>
        form={form}
        layout="vertical"
        initialValues={{ level: levels[0], value: initialValue }}
        onFinish={() => void submit()}
      >
        <Form.Item name="level" label={t('settings.set.level')} hidden={levels.length < 2}>
          <Radio.Group
            optionType="button"
            options={levels.map((l) => ({ value: l, label: t(`level.${l}`) }))}
          />
        </Form.Item>
        {level === 'LEGAL_ENTITY' ? (
          <Form.Item
            name="legalEntityId"
            label={tc('field.legalEntity')}
            rules={[{ required: true, message: t('settings.set.legalEntityRequired') }]}
          >
            <LegalEntitySelect style={{ width: 360 }} />
          </Form.Item>
        ) : null}
        {level === 'LOCATION' ? (
          <Form.Item
            name="locationId"
            label={tc('field.location')}
            rules={[{ required: true, message: t('settings.set.locationRequired') }]}
          >
            <LocationSelect style={{ width: 360 }} />
          </Form.Item>
        ) : null}
        <Form.Item
          name="value"
          label={t('settings.field.value')}
          valuePropName={type === 'BOOL' ? 'checked' : 'value'}
          rules={valueRules}
        >
          <SettingValueInput setting={setting} />
        </Form.Item>
        {setting.effectiveDated ? (
          <Form.Item name="validFrom" label={tc('field.validFrom')} extra={t('settings.set.validFromHint')}>
            <DatePicker format={DATE_FORMAT} />
          </Form.Item>
        ) : (
          <Typography.Paragraph type="secondary">{t('settings.set.notDatedHint')}</Typography.Paragraph>
        )}
        <Button type="primary" htmlType="submit" loading={set.isPending}>
          {t('settings.set.submit')}
        </Button>
      </Form>
    </>
  );
}

// ------------------------------------------------------------------------------------------------ resolution

function ResolvePanel({ setting }: { setting: Setting }) {
  const { t } = useTranslation(CONFIGURATION_NS);
  const { t: tc } = useTranslation();
  const [legalEntityId, setLegalEntityId] = useState<UUID | undefined>();
  const [locationId, setLocationId] = useState<UUID | undefined>();
  const [asOf, setAsOf] = useState<Dayjs | null>(dayjs());
  const [params, setParams] = useState<ResolveParams | null>(null);
  const query = useResolveSetting(setting.key, params ?? {}, params !== null);
  const scoped = setting.allowedLevels.length > 1;

  const check = () =>
    setParams({
      legalEntityId: legalEntityId || undefined,
      locationId: locationId || undefined,
      asOf: asOf ? asOf.format(ISO_DATE) : undefined,
    });

  return (
    <div>
      <Typography.Paragraph type="secondary">{t('settings.resolve.hint')}</Typography.Paragraph>
      <Space wrap align="start">
        {scoped ? (
          <LegalEntitySelect
            allowClear
            style={{ width: 260 }}
            value={legalEntityId}
            onChange={(v) => {
              setLegalEntityId(v);
              setLocationId(undefined);
            }}
          />
        ) : null}
        {setting.allowedLevels.includes('LOCATION') ? (
          <LocationSelect allowClear style={{ width: 260 }} legalEntityId={legalEntityId} value={locationId} onChange={setLocationId} />
        ) : null}
        {setting.effectiveDated ? (
          <DatePicker format={DATE_FORMAT} value={asOf} onChange={setAsOf} aria-label={tc('field.asOf')} placeholder={tc('field.asOf')} />
        ) : null}
        <Button onClick={check} loading={query.isFetching}>
          {t('settings.resolve.check')}
        </Button>
      </Space>
      {query.error ? <ProblemAlert error={query.error} style={{ marginTop: 12 }} /> : null}
      {params && query.data ? (
        <div style={{ marginTop: 12 }} data-testid="resolved-setting">
          <DescriptionList
            column={2}
            items={[
              { label: t('settings.resolve.result'), value: <SettingValueView type={setting.valueType} value={query.data.value} /> },
              {
                label: t('settings.resolve.source'),
                value: (
                  <Space size={4}>
                    <LevelTag level={query.data.source} />
                    {query.data.validFrom && !isOpenFrom(query.data.validFrom)
                      ? t('settings.resolve.since', { date: formatDate(query.data.validFrom) })
                      : null}
                  </Space>
                ),
              },
            ]}
          />
        </div>
      ) : null}
    </div>
  );
}
