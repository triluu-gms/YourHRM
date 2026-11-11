import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { HomePage } from './HomePage';

export const homeRoute = createRoute({ getParentRoute: () => adminRoute, path: '/', component: HomePage });

export const homeRoutes = [homeRoute];
