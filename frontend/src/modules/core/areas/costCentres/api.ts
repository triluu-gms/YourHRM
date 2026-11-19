import { http, type ISODate, type UUID } from '@/shared/api';

/*
 * Cost centres (BR-CORE-025, US-CORE-022). Types mirror `CostCentreService.CostCentreView` and the request records
 * of `CostCentreController` 1:1. Each cost centre belongs to one legal entity and is valid over
 * `[validFrom, validTo)` — `validTo` is exclusive, so the UI shows the day before as the last valid day.
 *
 * Optimistic locking: an update sends the `version` it read; a concurrent change is refused with 409 `stale-version`.
 */

/** `CostCentreService.CostCentreView`. */
export interface CostCentre {
  id: UUID;
  legalEntityId: UUID;
  code: string;
  name: string;
  glSegment?: string | null;
  ownerUserId?: UUID | null;
  validFrom: ISODate;
  validTo?: ISODate | null;
  version: number;
}

/** `CostCentreController.CreateCostCentreRequest`; `validFrom` null = today. */
export interface CreateCostCentreRequest {
  legalEntityId: UUID;
  code: string;
  name: string;
  glSegment?: string | null;
  ownerUserId?: UUID | null;
  validFrom?: ISODate | null;
  validTo?: ISODate | null;
}

/** `CostCentreController.UpdateCostCentreRequest`; legal entity, code and start date are immutable. */
export interface UpdateCostCentreRequest {
  name: string;
  glSegment?: string | null;
  ownerUserId?: UUID | null;
  validTo?: ISODate | null;
  version: number;
}

export interface CostCentreListParams {
  /** Cost centres valid on this date; ignored when `allDates`. */
  asOf?: ISODate;
  legalEntityId?: UUID;
  /** Every period, including ended and future ones. */
  allDates?: boolean;
}

/** Mirrors the backend `@Pattern` of the cost centre code. */
export const COST_CENTRE_CODE_PATTERN = /^[A-Za-z0-9_.-]{1,32}$/;

export const costCentresApi = {
  list: (params: CostCentreListParams) => http.get<CostCentre[]>('/core/cost-centres', { params: { ...params } }),
  get: (id: UUID) => http.get<CostCentre>(`/core/cost-centres/${id}`),
  create: (body: CreateCostCentreRequest) => http.post<CostCentre>('/core/cost-centres', body),
  update: (id: UUID, body: UpdateCostCentreRequest) => http.put<CostCentre>(`/core/cost-centres/${id}`, body),
};
