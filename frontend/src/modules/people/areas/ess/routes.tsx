import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { PeoplePermissions as P } from '../../permissions';
import { MyProfilePage } from './MyProfilePage';
import { MyFamilyPage } from './MyFamilyPage';
import { MyBankAccountsPage } from './MyBankAccountsPage';
import { MyDocumentsPage } from './MyDocumentsPage';
import { MyResignationPage } from './MyResignationPage';

/**
 * Employee self-service pages (EP-PPL-10), in the admin shell until the ESS shell exists. The employee is always
 * the caller (SELF scope) — no ids in the paths. Writes are requests that HR approves.
 */
export const myProfileRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/me',
  beforeLoad: requirePermission(P.ESS_VIEW),
  component: MyProfilePage,
});
export const myFamilyRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/me/family',
  beforeLoad: requirePermission(P.ESS_VIEW),
  component: MyFamilyPage,
});
export const myBankAccountsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/me/bank-accounts',
  beforeLoad: requirePermission(P.ESS_VIEW),
  component: MyBankAccountsPage,
});
export const myDocumentsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/me/documents',
  beforeLoad: requirePermission(P.CONTRACT_VIEW),
  component: MyDocumentsPage,
});
export const myResignationRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/people/me/resignation',
  beforeLoad: requirePermission(P.ESS_VIEW),
  component: MyResignationPage,
});

export const essRoutes = [myProfileRoute, myFamilyRoute, myBankAccountsRoute, myDocumentsRoute, myResignationRoute];
