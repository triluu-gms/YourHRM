import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { MePage } from './MePage';

export const meRoute = createRoute({ getParentRoute: () => adminRoute, path: '/core/me', component: MePage });

export const meRoutes = [meRoute];
