import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { TasksPage } from './TasksPage';
import { MyRequestsPage } from './MyRequestsPage';
import { InstanceDetailPage } from './InstanceDetailPage';
import { ConsolePage } from './ConsolePage';
import { DefinitionsPage } from './DefinitionsPage';
import { DefinitionDetailPage } from './DefinitionDetailPage';

/** Workflow area (BR-CORE-050..061). Inbox, requests and the request detail are per-task/per-instance authorised by the backend. */
export const workflowTasksRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow/tasks',
  component: TasksPage,
});
export const workflowRequestsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow/requests',
  component: MyRequestsPage,
});
export const workflowInstanceRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow/instances/$instanceId',
  component: function WorkflowInstanceRoute() {
    return <InstanceDetailPage instanceId={workflowInstanceRoute.useParams().instanceId} />;
  },
});
/**
 * The same request detail under the requests path: notification deep links point here
 * (`WorkflowNotifier`), and it is the natural child of "My requests".
 */
export const workflowRequestDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow/requests/$instanceId',
  component: function WorkflowRequestDetailRoute() {
    return <InstanceDetailPage instanceId={workflowRequestDetailRoute.useParams().instanceId} />;
  },
});
export const workflowConsoleRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow/console',
  beforeLoad: requirePermission(P.WORKFLOW_ADMIN),
  component: ConsolePage,
});
export const workflowDefinitionsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow-definitions',
  beforeLoad: requirePermission(P.WORKFLOW_DEFINITION_VIEW),
  component: DefinitionsPage,
});
export const workflowDefinitionDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow-definitions/$definitionId',
  beforeLoad: requirePermission(P.WORKFLOW_DEFINITION_VIEW),
  component: function WorkflowDefinitionDetailRoute() {
    return <DefinitionDetailPage definitionId={workflowDefinitionDetailRoute.useParams().definitionId} />;
  },
});

export const workflowRoutes = [
  workflowTasksRoute,
  workflowRequestsRoute,
  workflowRequestDetailRoute,
  workflowInstanceRoute,
  workflowConsoleRoute,
  workflowDefinitionsRoute,
  workflowDefinitionDetailRoute,
];
