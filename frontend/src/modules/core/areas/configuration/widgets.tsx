import { useMemo, type CSSProperties } from 'react';
import { DatePicker, Input, InputNumber, Select, Switch, Tag, Tooltip, Typography } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ISODate, UUID } from '@/shared/api';
import { DATE_FORMAT } from '@/shared/format';
import { useLegalEntities, useLocations } from '../employer/queries';
import { legalEntityLabel } from '../employer/widgets';
import type { Location } from '../employer/api';
import type { Setting, SettingJson, SettingSource, SettingValueType } from './api';
import { isMasked, settingValueText } from './format';
import { CONFIGURATION_NS } from './i18n';

const SOURCE_COLOURS: Record<SettingSource, string> = {
  LOCATION: 'purple',
  LEGAL_ENTITY: 'blue',
  TENANT: 'green',
  DEFAULT: 'default',
};

/** A level (or `DEFAULT`) as a coloured tag. */
export function LevelTag({ level }: { level: SettingSource }) {
  const { t } = useTranslation(CONFIGURATION_NS);
  return <Tag color={SOURCE_COLOURS[level]}>{t(`level.${level}`)}</Tag>;
}

/** A value rendered for its type: translated on/off, dd/MM/yyyy dates, numbers, compact JSON; masked when sensitive. */
export function SettingValueView({ type, value }: { type: SettingValueType; value: SettingJson }) {
  const { t } = useTranslation(CONFIGURATION_NS);
  if (isMasked(value)) {
    return (
      <Tooltip title={t('common.masked')}>
        <Typography.Text type="secondary" aria-label={t('common.masked')}>
          <LockOutlined /> ***
        </Typography.Text>
      </Tooltip>
    );
  }
  if (type === 'BOOL') {
    return <Tag color={value === true ? 'green' : 'default'}>{t(value === true ? 'common.true' : 'common.false')}</Tag>;
  }
  const text = settingValueText(type, value);
  if (type === 'JSON') {
    return (
      <Typography.Text code ellipsis={{ tooltip: text }} style={{ maxWidth: 320 }}>
        {text}
      </Typography.Text>
    );
  }
  return <span>{text || '—'}</span>;
}

/**
 * Typed editor for a setting's value, used as a `Form.Item` child (`value` / `onChange` / `checked`). Dates are
 * dayjs values and JSON is text; `fromEditorValue` turns them into the request value.
 */
export function SettingValueInput({
  setting,
  id,
  value,
  checked,
  onChange,
}: {
  setting: Pick<Setting, 'valueType' | 'allowedValues'>;
  id?: string;
  value?: unknown;
  checked?: boolean;
  onChange?: (v: unknown) => void;
}) {
  const { t } = useTranslation(CONFIGURATION_NS);
  switch (setting.valueType) {
    case 'BOOL':
      return (
        <Switch
          id={id}
          checked={checked === true}
          onChange={(v) => onChange?.(v)}
          checkedChildren={t('common.true')}
          unCheckedChildren={t('common.false')}
        />
      );
    case 'INT':
      return <InputNumber id={id} precision={0} style={{ width: 200 }} value={value as number} onChange={onChange} />;
    case 'DECIMAL':
      return <InputNumber id={id} style={{ width: 200 }} value={value as number} onChange={onChange} />;
    case 'ENUM':
      return (
        <Select
          id={id}
          style={{ minWidth: 200 }}
          value={value as string}
          onChange={onChange}
          options={setting.allowedValues.map((v) => ({ value: v, label: v }))}
        />
      );
    case 'DATE':
      return <DatePicker id={id} format={DATE_FORMAT} value={value as never} onChange={onChange} />;
    case 'JSON':
      return (
        <Input.TextArea
          id={id}
          rows={6}
          spellCheck={false}
          style={{ fontFamily: 'monospace' }}
          value={value as string}
          onChange={(e) => onChange?.(e.target.value)}
        />
      );
    default:
      return <Input id={id} value={value as string} onChange={(e) => onChange?.(e.target.value)} />;
  }
}

export const locationLabel = (loc: Location) => (loc.current?.name ? `${loc.code} – ${loc.current.name}` : loc.code);

/** Location picker, optionally narrowed to one legal entity; closed locations are hidden unless selected. */
export function LocationSelect({
  value,
  onChange,
  legalEntityId,
  asOf,
  id,
  allowClear,
  style,
}: {
  value?: UUID | null;
  onChange?: (id: UUID | undefined) => void;
  legalEntityId?: UUID;
  asOf?: ISODate;
  id?: string;
  allowClear?: boolean;
  style?: CSSProperties;
}) {
  const { t } = useTranslation(CONFIGURATION_NS);
  const query = useLocations(asOf, legalEntityId);
  return (
    <Select
      id={id}
      showSearch
      optionFilterProp="label"
      allowClear={allowClear}
      style={style}
      aria-label={t('common.selectLocation')}
      placeholder={t('common.selectLocation')}
      loading={query.isLoading}
      value={value ?? undefined}
      onChange={(v) => onChange?.(v ?? undefined)}
      options={(query.data ?? [])
        .filter((l) => l.status !== 'CLOSED' || l.id === value)
        .map((l) => ({ value: l.id, label: locationLabel(l) }))}
    />
  );
}

/**
 * Names of legal entities and locations for scope columns. When the caller cannot list them (no employer view
 * permission) the lookups fail quietly and the id is shown instead.
 */
export function useScopeNames() {
  const les = useLegalEntities();
  const locs = useLocations();
  return useMemo(() => {
    const leNames = new Map((les.data ?? []).map((le) => [le.id, legalEntityLabel(le)]));
    const locNames = new Map((locs.data ?? []).map((l) => [l.id, locationLabel(l)]));
    return {
      legalEntity: (id?: UUID | null) => (id ? (leNames.get(id) ?? id) : ''),
      location: (id?: UUID | null) => (id ? (locNames.get(id) ?? id) : ''),
      legalEntityCode: (id?: UUID | null) => (les.data ?? []).find((le) => le.id === id)?.code,
    };
  }, [les.data, locs.data]);
}
