import { createRoute, type AnyRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { AttendancePermissions as P } from '../../permissions';
import { HolidayCalendarsPage } from './HolidayCalendarsPage';
import { HolidayCalendarDetailPage } from './HolidayCalendarDetailPage';
import './i18n';

/** Holiday calendars (BRD 03 §4.1, US-ATT-001..007). Paths are linked from `menu.ts`; keep them. */
export const holidayCalendarsRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/holiday-calendars',
  beforeLoad: requirePermission(P.HOLIDAY_CALENDAR_VIEW),
  component: HolidayCalendarsPage,
});

export const holidayCalendarDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/attendance/holiday-calendars/$calendarId',
  beforeLoad: requirePermission(P.HOLIDAY_CALENDAR_VIEW),
  component: function HolidayCalendarDetailRoute() {
    return <HolidayCalendarDetailPage calendarId={holidayCalendarDetailRoute.useParams().calendarId} />;
  },
});

export const calendarsRoutes: AnyRoute[] = [holidayCalendarsRoute, holidayCalendarDetailRoute];
