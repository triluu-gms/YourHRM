import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { PeoplePermissions as P } from '../../permissions';
import { ContractDetailPage } from './ContractDetailPage';
import { DecisionDetailPage } from './DecisionDetailPage';
import { ExpiringContractsPage, ProbationsEndingPage } from './ExpiryListPages';

/** Contracts & decisions area (EP-PPL-06, US-PPL-047..058). Paths are linked from `menu.ts` and other areas. */
export const expiringContractsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/contracts/expiring',
  beforeLoad: requirePermission(P.CONTRACT_VIEW),
  component: ExpiringContractsPage,
});
export const probationsEndingRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/contracts/probations-ending',
  beforeLoad: requirePermission(P.CONTRACT_VIEW),
  component: ProbationsEndingPage,
});
export const contractDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/contracts/$contractId',
  beforeLoad: requirePermission(P.CONTRACT_VIEW),
  component: function ContractDetailRoute() {
    return <ContractDetailPage contractId={contractDetailRoute.useParams().contractId} />;
  },
});
export const decisionDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/decisions/$decisionId',
  beforeLoad: requirePermission(P.DECISION_VIEW),
  component: function DecisionDetailRoute() {
    return <DecisionDetailPage decisionId={decisionDetailRoute.useParams().decisionId} />;
  },
});

export const contractsRoutes = [expiringContractsRoute, probationsEndingRoute, contractDetailRoute, decisionDetailRoute];
