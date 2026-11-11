import type { MenuEntry } from '@/shared/navigation';
import { CorePermissions as P } from './permissions';

/** Core's contribution to the admin menu. Paths are owned by `areas/<area>/routes.tsx`. */
export const coreMenu: MenuEntry[] = [
  {
    key: 'employer',
    labelKey: 'nav.employer',
    icon: 'bank',
    children: [
      { key: 'company-group', labelKey: 'nav.companyGroup', path: '/core/company-group', permissions: [P.COMPANY_GROUP_VIEW] },
      { key: 'legal-entities', labelKey: 'nav.legalEntities', path: '/core/legal-entities', permissions: [P.LEGAL_ENTITY_VIEW] },
      { key: 'locations', labelKey: 'nav.locations', path: '/core/locations', permissions: [P.LOCATION_VIEW] },
      { key: 'org-units', labelKey: 'nav.orgUnits', path: '/core/org-units', permissions: [P.ORG_UNIT_VIEW] },
      { key: 'org-unit-types', labelKey: 'nav.orgUnitTypes', path: '/core/org-unit-types', permissions: [P.ORG_UNIT_VIEW] },
    ],
  },
  {
    key: 'job-catalogue',
    labelKey: 'nav.jobCatalogue',
    icon: 'apartment',
    children: [
      { key: 'job-titles', labelKey: 'nav.jobTitles', path: '/core/job-titles', permissions: [P.JOB_CATALOGUE_VIEW] },
      { key: 'grade-structures', labelKey: 'nav.gradeStructures', path: '/core/grade-structures', permissions: [P.JOB_CATALOGUE_VIEW] },
      { key: 'cost-centres', labelKey: 'nav.costCentres', path: '/core/cost-centres', permissions: [P.COST_CENTRE_VIEW] },
      { key: 'employee-categories', labelKey: 'nav.employeeCategories', path: '/core/employee-categories', permissions: [P.EMPLOYEE_CATEGORY_VIEW] },
    ],
  },
  {
    key: 'access',
    labelKey: 'nav.access',
    icon: 'safety',
    children: [
      { key: 'users', labelKey: 'nav.users', path: '/core/users', permissions: [P.USER_VIEW] },
      { key: 'roles', labelKey: 'nav.roles', path: '/core/roles', permissions: [P.ROLE_VIEW] },
      { key: 'permissions', labelKey: 'nav.permissions', path: '/core/permissions', permissions: [P.PERMISSION_VIEW] },
      { key: 'support-sessions', labelKey: 'nav.supportSessions', path: '/core/support-sessions', permissions: [P.SUPPORT_SESSION_VIEW] },
    ],
  },
  {
    key: 'workflow',
    labelKey: 'nav.workflow',
    icon: 'check',
    children: [
      { key: 'my-tasks', labelKey: 'nav.myTasks', path: '/core/workflow/tasks' },
      { key: 'my-requests', labelKey: 'nav.myRequests', path: '/core/workflow/requests' },
      { key: 'workflow-console', labelKey: 'nav.workflowConsole', path: '/core/workflow/console', permissions: [P.WORKFLOW_ADMIN] },
      { key: 'workflow-definitions', labelKey: 'nav.workflowDefinitions', path: '/core/workflow-definitions', permissions: [P.WORKFLOW_DEFINITION_VIEW] },
    ],
  },
  {
    key: 'notifications',
    labelKey: 'nav.notifications',
    icon: 'bell',
    children: [
      { key: 'notification-inbox', labelKey: 'nav.notificationInbox', path: '/core/notifications' },
      { key: 'notification-deliveries', labelKey: 'nav.notificationDeliveries', path: '/core/notification-deliveries', permissions: [P.NOTIFICATION_ADMIN] },
      { key: 'notification-templates', labelKey: 'nav.notificationTemplates', path: '/core/notification-templates', permissions: [P.NOTIFICATION_TEMPLATE_VIEW] },
    ],
  },
  {
    key: 'documents',
    labelKey: 'nav.documents',
    icon: 'file',
    children: [
      { key: 'document-library', labelKey: 'nav.documentLibrary', path: '/core/documents' },
      { key: 'document-templates', labelKey: 'nav.documentTemplates', path: '/core/document-templates', permissions: [P.DOCUMENT_TEMPLATE_VIEW] },
    ],
  },
  { key: 'imports', labelKey: 'nav.imports', icon: 'import', path: '/core/imports', permissions: [P.IMPORT_VIEW] },
  {
    key: 'configuration',
    labelKey: 'nav.configuration',
    icon: 'setting',
    children: [
      { key: 'settings', labelKey: 'nav.settings', path: '/core/settings', permissions: [P.SETTING_VIEW] },
      { key: 'sequences', labelKey: 'nav.sequences', path: '/core/sequences', permissions: [P.SEQUENCE_VIEW] },
      { key: 'master-data', labelKey: 'nav.masterData', path: '/core/master-data' },
    ],
  },
  { key: 'audit', labelKey: 'nav.audit', icon: 'audit', path: '/core/audit', permissions: [P.AUDIT_VIEW] },
];
