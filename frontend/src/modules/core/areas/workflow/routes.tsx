import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { Placeholder } from '../Placeholder';

/** Workflow inbox, requests, console, definitions area. Replace the placeholders with the real pages; keep the paths (menu.ts links to them). */
export const workflowTasksRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow/tasks',
  component: () => <Placeholder area="/core/workflow/tasks" />,
});
export const workflowRequestsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow/requests',
  component: () => <Placeholder area="/core/workflow/requests" />,
});
export const workflowConsoleRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow/console',
  component: () => <Placeholder area="/core/workflow/console" />,
});
export const workflowDefinitionsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/workflow-definitions',
  component: () => <Placeholder area="/core/workflow-definitions" />,
});

export const workflowRoutes = [workflowTasksRoute, workflowRequestsRoute, workflowConsoleRoute, workflowDefinitionsRoute ];
