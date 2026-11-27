import { Alert } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';
import type { ISODate, UUID } from '@/shared/api';
import { ISO_DATE, localizedName } from '@/shared/format';
import { legalEntityLabel, useLegalEntities, useLocations, useOrgUnits } from '../../common';
import type { LocalTime } from './api';
import { SCHEDULES_NS } from './i18n';

export const TIME_FORMAT = 'HH:mm';

/** `08:00:00` → `08:00` for display. */
export function hhmm(t: LocalTime | null | undefined): string {
  return t ? t.slice(0, 5) : '';
}

export function toTime(t: LocalTime | null | undefined): Dayjs | null {
  if (!t) return null;
  const [h = 0, m = 0] = t.split(':').map(Number);
  return dayjs().hour(h).minute(m).second(0).millisecond(0);
}

export function fromTime(d: Dayjs | null | undefined): LocalTime | null {
  return d ? d.format(TIME_FORMAT) : null;
}

export function iso(d: Dayjs | null | undefined): ISODate | null {
  return d ? d.format(ISO_DATE) : null;
}

/** Pattern A: the UI asks for the last day (inclusive); the API takes the exclusive end. */
export function toExclusiveEnd(lastDay: Dayjs | null | undefined): ISODate | null {
  return lastDay ? lastDay.add(1, 'day').format(ISO_DATE) : null;
}

export function fromExclusiveEnd(validTo: ISODate | null | undefined): Dayjs | null {
  return validTo ? dayjs(validTo).subtract(1, 'day') : null;
}

export function clean(v: string | null | undefined): string | null {
  if (v === undefined || v === null) return null;
  const s = v.trim();
  return s === '' ? null : s;
}

export function minutesLabel(min: number): string {
  const h = Math.floor(min / 60);
  const m = min % 60;
  return `${h}:${String(m).padStart(2, '0')}`;
}

/** Labels for Core lookups shown in tables (legal entity, org unit, location). */
export function useLookupLabels() {
  const { i18n } = useTranslation();
  const les = useLegalEntities();
  const ous = useOrgUnits();
  const locs = useLocations();
  return {
    legalEntity: (id?: UUID | null) => (id ? legalEntityLabel(les.data?.find((x) => x.id === id), i18n.language) || id : ''),
    orgUnit: (id?: UUID | null) => {
      if (!id) return '';
      const ou = ous.data?.find((x) => x.id === id);
      return ou ? `${ou.code} – ${localizedName(ou, i18n.language)}` : id;
    },
    location: (id?: UUID | null) => {
      if (!id) return '';
      const l = locs.data?.find((x) => x.id === id);
      return l ? (l.current?.name ? `${l.code} – ${l.current.name}` : l.code) : id;
    },
  };
}

/**
 * Statutory warnings of a saved shift / pattern (`code: detail` or `legal-parameter-missing:KEY (...)`): the code is
 * translated, the backend's detail kept as it carries the figures.
 */
export function WarningsAlert({ warnings, onClose }: { warnings: string[]; onClose?: () => void }) {
  const { t } = useTranslation(SCHEDULES_NS);
  if (warnings.length === 0) return null;
  return (
    <Alert
      type="warning"
      showIcon
      closable={!!onClose}
      onClose={onClose}
      style={{ marginBottom: 12 }}
      message={t('warning.title')}
      description={
        <ul style={{ margin: 0, paddingLeft: 16 }}>
          {warnings.map((w, i) => {
            const m = /^([a-z][a-z-]*)\s*:\s*(.*)$/.exec(w);
            const code = m?.[1] ?? '';
            const detail = m?.[2] ?? w;
            return (
              <li key={i}>
                {code ? <b>{t(`warning.${code}`, { defaultValue: code })}</b> : null}
                {code ? ': ' : null}
                {detail}
              </li>
            );
          })}
        </ul>
      }
    />
  );
}
