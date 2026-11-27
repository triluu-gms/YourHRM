import { createRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { AttendancePermissions as P } from '../../permissions';
import { DevicesPage } from './DevicesPage';
import { EnrolmentMappingsPage } from './EnrolmentMappingsPage';
import { QuarantinePage } from './QuarantinePage';
import './i18n';

/** Punch sources (BRD 03 §4.3). Paths are linked from `menu.ts`. */
export const devicesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/devices',
  beforeLoad: requirePermission(P.DEVICE_VIEW),
  component: DevicesPage,
});

export const enrolmentMappingsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/enrolment-mappings',
  validateSearch: z.object({ deviceId: z.string().uuid().optional() }),
  beforeLoad: requirePermission(P.DEVICE_VIEW),
  component: function EnrolmentMappingsRoute() {
    const { deviceId } = enrolmentMappingsRoute.useSearch();
    return <EnrolmentMappingsPage key={deviceId ?? ''} initialDeviceId={deviceId} />;
  },
});

export const punchQuarantineRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/punches/quarantine',
  beforeLoad: requirePermission(P.PUNCH_VIEW),
  component: QuarantinePage,
});

export const devicesRoutes = [devicesRoute, enrolmentMappingsRoute, punchQuarantineRoute];
