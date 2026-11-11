/** Types shared by every module's API client; they mirror the backend records 1:1. */

export type UUID = string;
/** ISO date `yyyy-MM-dd` (business dates are LocalDate on the wire, ADR-0034). */
export type ISODate = string;
/** ISO-8601 instant in UTC (display in Asia/Ho_Chi_Minh). */
export type ISOInstant = string;

/** Offset page as returned by the Core list endpoints (`UserService.Page`). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** Audit search page: no total, only `hasMore`. */
export interface CursorlessPage<T> {
  items: T[];
  page: number;
  size: number;
  hasMore: boolean;
}

export interface PageRequest {
  page?: number;
  size?: number;
}

/** Bilingual names everywhere in Core (`nameVi` / `nameEn`). */
export interface Bilingual {
  nameVi: string;
  nameEn?: string | null;
}

/** Pattern A effective dating: `validTo` is exclusive and null = open-ended (ADR-0024). */
export interface EffectiveDated {
  validFrom: ISODate;
  validTo?: ISODate | null;
}

/** Optimistic locking: every PUT carries the version the client read (`stale-version` on mismatch). */
export interface Versioned {
  version: number;
}
