import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { CompanyGroupPage } from './CompanyGroupPage';
import { LegalEntitiesPage } from './LegalEntitiesPage';
import { LegalEntityDetailPage } from './LegalEntityDetailPage';
import { LocationsPage } from './LocationsPage';
import { LocationDetailPage } from './LocationDetailPage';
import { OrgUnitsPage } from './OrgUnitsPage';
import { OrgUnitDetailPage } from './OrgUnitDetailPage';
import { OrgUnitTypesPage } from './OrgUnitTypesPage';

/** Employer structure area (BR-CORE-001..012). Paths are linked from `menu.ts`; the backend stays authoritative. */
export const companyGroupRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/company-group',
  beforeLoad: requirePermission(P.COMPANY_GROUP_VIEW),
  component: CompanyGroupPage,
});
export const legalEntitiesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/legal-entities',
  beforeLoad: requirePermission(P.LEGAL_ENTITY_VIEW),
  component: LegalEntitiesPage,
});
export const legalEntityDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/legal-entities/$legalEntityId',
  beforeLoad: requirePermission(P.LEGAL_ENTITY_VIEW),
  component: function LegalEntityDetailRoute() {
    return <LegalEntityDetailPage legalEntityId={legalEntityDetailRoute.useParams().legalEntityId} />;
  },
});
export const locationsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/locations',
  beforeLoad: requirePermission(P.LOCATION_VIEW),
  component: LocationsPage,
});
export const locationDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/locations/$locationId',
  beforeLoad: requirePermission(P.LOCATION_VIEW),
  component: function LocationDetailRoute() {
    return <LocationDetailPage locationId={locationDetailRoute.useParams().locationId} />;
  },
});
export const orgUnitsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/org-units',
  beforeLoad: requirePermission(P.ORG_UNIT_VIEW),
  component: OrgUnitsPage,
});
export const orgUnitDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/org-units/$orgUnitId',
  beforeLoad: requirePermission(P.ORG_UNIT_VIEW),
  component: function OrgUnitDetailRoute() {
    return <OrgUnitDetailPage orgUnitId={orgUnitDetailRoute.useParams().orgUnitId} />;
  },
});
/** Org unit types are read with `core.org_unit.view` (OrgUnitTypeController GET). */
export const orgUnitTypesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/org-unit-types',
  beforeLoad: requirePermission(P.ORG_UNIT_VIEW),
  component: OrgUnitTypesPage,
});

export const employerRoutes = [
  companyGroupRoute,
  legalEntitiesRoute,
  legalEntityDetailRoute,
  locationsRoute,
  locationDetailRoute,
  orgUnitsRoute,
  orgUnitDetailRoute,
  orgUnitTypesRoute,
];
