import { http, type ISODate, type ISOInstant, type Page, type UUID } from '@/shared/api';

/**
 * Types mirror `WorkflowController`, `WorkflowDefinitionController`, `WorkflowQueryService` and
 * `WorkflowDefinitionService` records 1:1 (field names exactly as serialised by Jackson).
 */

/** `vn.yourhrm.shared.workflow.api.WorkflowAction`. */
export type WorkflowAction =
  | 'APPROVE'
  | 'REJECT'
  | 'RETURN'
  | 'REQUEST_INFO'
  | 'PROVIDE_INFO'
  | 'WITHDRAW'
  | 'FORWARD'
  | 'COMMENT'
  | 'RESUBMIT'
  | 'BULK_APPROVE';

/** Instance statuses written by `WorkflowEngine`. */
export type InstanceStatus =
  | 'IN_PROGRESS'
  | 'RETURNED'
  | 'INFO_REQUESTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'WITHDRAWN'
  | 'CANCELLED'
  | string;

export type Summary = Record<string, unknown>;

/** `WorkflowQueryService.TaskItem`. */
export interface TaskItem {
  taskId: UUID;
  instanceId: UUID;
  processTypeCode: string;
  processNameVi: string;
  processNameEn?: string | null;
  stepNo: number;
  stepKey: string;
  stepNameVi?: string | null;
  requesterUserId: UUID;
  requesterName?: string | null;
  submittedAt: ISOInstant;
  assignedAt: ISOInstant;
  instanceStatus: InstanceStatus;
  summary: Summary;
}

/** `WorkflowQueryService.RequestItem`. */
export interface RequestItem {
  instanceId: UUID;
  processTypeCode: string;
  processNameVi: string;
  processNameEn?: string | null;
  subjectType?: string | null;
  subjectId?: UUID | null;
  status: InstanceStatus;
  outcome?: string | null;
  currentStep?: number | null;
  submittedAt: ISOInstant;
  completedAt?: ISOInstant | null;
  summary: Summary;
}

/** `WorkflowQueryService.TaskView`. */
export interface TaskView {
  taskId: UUID;
  stepNo: number;
  stepKey: string;
  stepType?: string | null;
  assigneeUserId?: UUID | null;
  originalAssigneeUserId?: UUID | null;
  assignedVia?: string | null;
  status: string;
  outcome?: string | null;
  actedByUserId?: UUID | null;
  actedAt?: ISOInstant | null;
  comment?: string | null;
}

/** `WorkflowQueryService.TrailEntry` (immutable approval history, BR-CORE-061). */
export interface TrailEntry {
  occurredAt: ISOInstant;
  action: string;
  actorType?: string | null;
  actorUserId?: UUID | null;
  actorName?: string | null;
  onBehalfOfUserId?: UUID | null;
  stepNo?: number | null;
  fromStatus?: string | null;
  toStatus?: string | null;
  comment?: string | null;
  attachmentIds: UUID[];
  channel?: string | null;
  clientIp?: string | null;
  userAgent?: string | null;
}

/** `ApproverChainResolver.ResolvedStep`. */
export interface ResolvedStep {
  stepNo: number;
  stepKey: string;
  nameVi: string;
  nameEn?: string | null;
  approvers: UUID[];
  via?: string | null;
  reason?: string | null;
  allowReturn: boolean;
}

/** `WorkflowQueryService.InstanceDetail`. */
export interface InstanceDetail {
  instanceId: UUID;
  processTypeCode: string;
  ownerModule?: string | null;
  subjectType?: string | null;
  subjectId?: UUID | null;
  requesterUserId: UUID;
  onBehalfOfUserId?: UUID | null;
  legalEntityId?: UUID | null;
  orgUnitId?: UUID | null;
  status: InstanceStatus;
  outcome?: string | null;
  currentStep?: number | null;
  submittedAt: ISOInstant;
  completedAt?: ISOInstant | null;
  summary: Summary;
  approverChain: ResolvedStep[];
  tasks: TaskView[];
  trail: TrailEntry[];
  allowedActions: string[];
  myPendingTaskId?: UUID | null;
}

/** `WorkflowQueryService.ConsoleItem`. */
export interface ConsoleItem {
  instanceId: UUID;
  processTypeCode: string;
  status: InstanceStatus;
  submittedAt: ISOInstant;
  ageHours: number;
  requesterUserId: UUID;
  callbackStatus?: string | null;
  lastError?: string | null;
}

/** `WorkflowQueryService.CallbackItem`. */
export interface CallbackItem {
  callbackId: UUID;
  instanceId: UUID;
  targetModule: string;
  outcome: string;
  status: string;
  attempts: number;
  lastError?: string | null;
  createdAt: ISOInstant;
}

/** `WorkflowEngine.BulkResult`. */
export interface BulkResult {
  taskId: UUID;
  approved: boolean;
  error?: string | null;
}

// ------------------------------------------------------------------------------------------ requests

/** `WorkflowController.TaskActionRequest`. */
export interface TaskActionRequest {
  action: WorkflowAction;
  comment?: string | null;
  attachmentIds?: UUID[] | null;
  forwardTo?: UUID | null;
  returnTo?: string | null;
}

/** `WorkflowController.InstanceActionRequest`. */
export interface InstanceActionRequest {
  action: WorkflowAction;
  comment?: string | null;
  attachmentIds?: UUID[] | null;
}

export interface BulkApproveRequest {
  taskIds: UUID[];
  comment?: string | null;
}

export interface ReassignRequest {
  toUserId: UUID;
  reason: string;
}

export interface ReassignAllRequest {
  fromUserId: UUID;
  toUserId: UUID;
  reason: string;
}

export interface CancelRequest {
  reason: string;
}

export interface ConsoleFilter {
  status?: string;
  processType?: string;
  olderThanHours?: number;
}

// ------------------------------------------------------------------------------------------ definitions

/** `ApproverResolver.Type`. */
export type ApproverResolverType =
  | 'LINE_MANAGER'
  | 'MANAGER_N_UP'
  | 'ORG_UNIT_MANAGER'
  | 'ROLE_IN_SCOPE'
  | 'USER'
  | 'REQUESTER_PICK'
  | 'REQUEST_FIELD'
  | 'POSITION_HOLDER'
  | 'FUNCTIONAL_MANAGER';

export interface ApproverResolver {
  type: ApproverResolverType;
  params: Record<string, string>;
}

/** `vn.yourhrm.shared.workflow.api.StepTemplate`. */
export interface StepTemplate {
  stepKey: string;
  nameVi: string;
  nameEn?: string | null;
  resolver: ApproverResolver;
  allowReturn: boolean;
}

/** `WorkflowDefinitionService.Conditions`; empty sets match anything. */
export interface Conditions {
  legalEntityIds: UUID[];
  orgUnitIds: UUID[];
  locationIds: UUID[];
  employeeCategoryIds: UUID[];
  fields: Record<string, string[]>;
}

export type ResolverFailurePolicy = 'SKIP' | 'ESCALATE_UP' | 'LE_HR_ADMIN' | 'FAIL';

/** `WorkflowDefinitionService.VersionView`. */
export interface VersionView {
  id: UUID;
  versionNo: number;
  status: string;
  validFrom: ISODate;
  validTo?: ISODate | null;
  conditions: Conditions;
  steps: StepTemplate[];
  onResolverFailure: ResolverFailurePolicy | string;
}

/** `WorkflowDefinitionService.DefinitionView`. */
export interface DefinitionView {
  id: UUID;
  processTypeCode: string;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  matchPriority: number;
  status: string;
  versions: VersionView[];
}

/** `WorkflowDefinitionService.ProcessTypeView`. */
export interface ProcessTypeView {
  code: string;
  ownerModule: string;
  nameVi: string;
  nameEn?: string | null;
  conditionFields: string[];
  allowedActions: string[];
  allowsAutoApprove: boolean;
  requiresStepUp: boolean;
  active: boolean;
  hasVendorDefault: boolean;
}

/** `WorkflowDefinitionController.PublishDefinitionRequest`. */
export interface PublishDefinitionRequest {
  processTypeCode: string;
  code: string;
  nameVi: string;
  nameEn?: string | null;
  matchPriority?: number | null;
  conditions?: Partial<Conditions> | null;
  steps: StepTemplate[];
  onResolverFailure?: ResolverFailurePolicy | null;
  validFrom: ISODate;
}

/** Minimal projection of `UserService.UserView` for the user pickers (reassign, forward). */
export interface UserRef {
  id: UUID;
  login: string;
  displayName?: string | null;
  email?: string | null;
  status: string;
}

// ------------------------------------------------------------------------------------------ client

const WF = '/core/workflow';

export const workflowApi = {
  tasks: (processType?: string) => http.get<TaskItem[]>(`${WF}/tasks`, { params: { processType } }),
  taskCounts: () => http.get<Record<string, number>>(`${WF}/tasks/counts`),
  act: (taskId: UUID, body: TaskActionRequest) => http.post<void>(`${WF}/tasks/${taskId}/actions`, body),
  bulkApprove: (body: BulkApproveRequest) => http.post<BulkResult[]>(`${WF}/tasks/bulk-approve`, body),
  reassignTask: (taskId: UUID, body: ReassignRequest) => http.post<void>(`${WF}/tasks/${taskId}/reassign`, body),
  reassignAll: (body: ReassignAllRequest) => http.post<UUID[]>(`${WF}/reassignments`, body),

  requests: (status?: string) => http.get<RequestItem[]>(`${WF}/requests`, { params: { status } }),
  instance: (instanceId: UUID) => http.get<InstanceDetail>(`${WF}/instances/${instanceId}`),
  trail: (instanceId: UUID) => http.get<TrailEntry[]>(`${WF}/instances/${instanceId}/trail`),
  instanceAction: (instanceId: UUID, body: InstanceActionRequest) =>
    http.post<void>(`${WF}/instances/${instanceId}/actions`, body),
  cancel: (instanceId: UUID, body: CancelRequest) => http.post<void>(`${WF}/instances/${instanceId}/cancel`, body),

  consoleInstances: (filter: ConsoleFilter) => http.get<ConsoleItem[]>(`${WF}/console/instances`, { params: { ...filter } }),
  consoleCallbacks: () => http.get<CallbackItem[]>(`${WF}/console/callbacks`),
  retryCallback: (callbackId: UUID) => http.post<void>(`${WF}/console/callbacks/${callbackId}/retry`),

  processTypes: () => http.get<ProcessTypeView[]>('/core/process-types'),
  definitions: (processType?: string) => http.get<DefinitionView[]>('/core/workflow-definitions', { params: { processType } }),
  definition: (definitionId: UUID) => http.get<DefinitionView>(`/core/workflow-definitions/${definitionId}`),
  publishDefinition: (body: PublishDefinitionRequest) => http.post<DefinitionView>('/core/workflow-definitions', body),

  /** `GET /core/users?q=` (UserController.search) — only the fields the pickers need. */
  searchUsers: (q: string) => http.get<Page<UserRef>>('/core/users', { params: { q, status: 'ACTIVE', page: 0, size: 20 } }),
};
