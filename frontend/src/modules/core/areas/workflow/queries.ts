import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UUID } from '@/shared/api';
import { taskCountsQuery } from './TaskBadge';
import {
  workflowApi,
  type BulkApproveRequest,
  type CancelRequest,
  type ConsoleFilter,
  type InstanceActionRequest,
  type PublishDefinitionRequest,
  type ReassignAllRequest,
  type ReassignRequest,
  type TaskActionRequest,
} from './api';

export const workflowKeys = {
  all: ['core', 'workflow'] as const,
  tasks: (processType?: string) => ['core', 'workflow', 'tasks', 'list', { processType: processType ?? null }] as const,
  /** Same key as the header badge (`TaskBadge.taskCountsQuery`). */
  taskCounts: taskCountsQuery.queryKey,
  requests: (status?: string) => ['core', 'workflow', 'requests', { status: status ?? null }] as const,
  instance: (id: UUID) => ['core', 'workflow', 'instances', id] as const,
  trail: (id: UUID) => ['core', 'workflow', 'instances', id, 'trail'] as const,
  consoleInstances: (filter: ConsoleFilter) => ['core', 'workflow', 'console', 'instances', filter] as const,
  consoleCallbacks: ['core', 'workflow', 'console', 'callbacks'] as const,
  processTypes: ['core', 'workflow', 'process-types'] as const,
  definitions: (processType?: string) => ['core', 'workflow', 'definitions', { processType: processType ?? null }] as const,
  definition: (id: UUID) => ['core', 'workflow', 'definitions', id] as const,
  users: (q: string) => ['core', 'workflow', 'user-search', q] as const,
};

export const tasksQuery = (processType?: string) =>
  queryOptions({ queryKey: workflowKeys.tasks(processType), queryFn: () => workflowApi.tasks(processType) });
export const requestsQuery = (status?: string) =>
  queryOptions({ queryKey: workflowKeys.requests(status), queryFn: () => workflowApi.requests(status) });
export const instanceQuery = (id: UUID) =>
  queryOptions({ queryKey: workflowKeys.instance(id), queryFn: () => workflowApi.instance(id) });
export const trailQuery = (id: UUID) =>
  queryOptions({ queryKey: workflowKeys.trail(id), queryFn: () => workflowApi.trail(id) });
export const consoleInstancesQuery = (filter: ConsoleFilter) =>
  queryOptions({ queryKey: workflowKeys.consoleInstances(filter), queryFn: () => workflowApi.consoleInstances(filter) });
export const consoleCallbacksQuery = queryOptions({
  queryKey: workflowKeys.consoleCallbacks,
  queryFn: workflowApi.consoleCallbacks,
});
export const processTypesQuery = queryOptions({
  queryKey: workflowKeys.processTypes,
  queryFn: workflowApi.processTypes,
  staleTime: 5 * 60_000,
});
export const definitionsQuery = (processType?: string) =>
  queryOptions({ queryKey: workflowKeys.definitions(processType), queryFn: () => workflowApi.definitions(processType) });
export const definitionQuery = (id: UUID) =>
  queryOptions({ queryKey: workflowKeys.definition(id), queryFn: () => workflowApi.definition(id) });

export function useUserSearch(q: string) {
  return useQuery({
    queryKey: workflowKeys.users(q),
    queryFn: () => workflowApi.searchUsers(q),
    enabled: q.trim().length >= 1,
    staleTime: 30_000,
  });
}

/** Invalidates everything that can change after a task / instance action: inbox, counts, requests, detail, console. */
function useInvalidateWorkflow() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: workflowKeys.all });
}

export function useTaskAction() {
  const invalidate = useInvalidateWorkflow();
  return useMutation({
    mutationFn: ({ taskId, body }: { taskId: UUID; body: TaskActionRequest }) => workflowApi.act(taskId, body),
    onSuccess: () => void invalidate(),
  });
}

export function useBulkApprove() {
  const invalidate = useInvalidateWorkflow();
  return useMutation({
    mutationFn: (body: BulkApproveRequest) => workflowApi.bulkApprove(body),
    onSuccess: () => void invalidate(),
  });
}

export function useReassignTask() {
  const invalidate = useInvalidateWorkflow();
  return useMutation({
    mutationFn: ({ taskId, body }: { taskId: UUID; body: ReassignRequest }) => workflowApi.reassignTask(taskId, body),
    onSuccess: () => void invalidate(),
  });
}

export function useReassignAll() {
  const invalidate = useInvalidateWorkflow();
  return useMutation({
    mutationFn: (body: ReassignAllRequest) => workflowApi.reassignAll(body),
    onSuccess: () => void invalidate(),
  });
}

export function useInstanceAction() {
  const invalidate = useInvalidateWorkflow();
  return useMutation({
    mutationFn: ({ instanceId, body }: { instanceId: UUID; body: InstanceActionRequest }) =>
      workflowApi.instanceAction(instanceId, body),
    onSuccess: () => void invalidate(),
  });
}

export function useCancelInstance() {
  const invalidate = useInvalidateWorkflow();
  return useMutation({
    mutationFn: ({ instanceId, body }: { instanceId: UUID; body: CancelRequest }) => workflowApi.cancel(instanceId, body),
    onSuccess: () => void invalidate(),
  });
}

export function useRetryCallback() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (callbackId: UUID) => workflowApi.retryCallback(callbackId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['core', 'workflow', 'console'] }),
  });
}

export function usePublishDefinition() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: PublishDefinitionRequest) => workflowApi.publishDefinition(body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['core', 'workflow', 'definitions'] }),
  });
}
