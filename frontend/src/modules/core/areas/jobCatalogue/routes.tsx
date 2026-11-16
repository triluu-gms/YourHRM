import { createRoute } from '@tanstack/react-router';
import { adminRoute } from '@/shared/routing';
import { requirePermission } from '@/shared/auth';
import { CorePermissions as P } from '../../permissions';
import { JobTitlesPage } from './JobTitlesPage';
import { JobTitleDetailPage } from './JobTitleDetailPage';
import { GradeStructuresPage } from './GradeStructuresPage';
import { GradeStructureDetailPage } from './GradeStructureDetailPage';

/**
 * Job catalogue area (BR-CORE-020/021): job titles + job families, grade structures + grades. Cost centres and
 * employee categories have their own areas. Paths are linked from `menu.ts`; keep them.
 */
export const jobTitlesRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/job-titles',
  beforeLoad: requirePermission(P.JOB_CATALOGUE_VIEW),
  component: JobTitlesPage,
});
export const jobTitleDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/job-titles/$titleId',
  beforeLoad: requirePermission(P.JOB_CATALOGUE_VIEW),
  component: function JobTitleDetailRoute() {
    return <JobTitleDetailPage titleId={jobTitleDetailRoute.useParams().titleId} />;
  },
});
export const gradeStructuresRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/grade-structures',
  beforeLoad: requirePermission(P.JOB_CATALOGUE_VIEW),
  component: GradeStructuresPage,
});
export const gradeStructureDetailRoute = createRoute({
  getParentRoute: () => adminRoute,
  path: '/core/grade-structures/$structureId',
  beforeLoad: requirePermission(P.JOB_CATALOGUE_VIEW),
  component: function GradeStructureDetailRoute() {
    return <GradeStructureDetailPage structureId={gradeStructureDetailRoute.useParams().structureId} />;
  },
});
export const jobCatalogueRoutes = [
  jobTitlesRoute,
  jobTitleDetailRoute,
  gradeStructuresRoute,
  gradeStructureDetailRoute,
];
