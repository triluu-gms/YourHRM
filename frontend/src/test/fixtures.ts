import type { Me } from '@/shared/auth';

export const TENANT_ID = '018f0000-0000-7000-8000-000000000001';
export const USER_ID = '018f0000-0000-7000-8000-000000000002';

/** A tenant admin holding every Core and People permission tenant-wide. */
export const meFixture: Me = {
  user: { id: USER_ID, login: 'admin', email: 'admin@example.test', status: 'ACTIVE', employmentId: null, identityId: null },
  tenant: { id: TENANT_ID, slug: 'demo', name: 'Demo Company', state: 'ACTIVE', defaultLocale: 'vi-VN', timeZone: 'Asia/Ho_Chi_Minh' },
  locale: 'vi-VN',
  asOf: '2026-11-10',
  roles: [
    {
      assignmentId: '018f0000-0000-7000-8000-000000000010',
      roleId: '018f0000-0000-7000-8000-000000000011',
      code: 'TENANT_OWNER',
      nameVi: 'Chủ tenant',
      nameEn: 'Tenant owner',
      scopeType: 'TENANT',
      scopeIds: [],
      includeSubtree: true,
      validFrom: '2026-01-01',
      validTo: null,
    },
  ],
  permissions: [
    'core.permission.view', 'core.role.view', 'core.role.create', 'core.role.update', 'core.role.delete',
    'core.user.view', 'core.user.create', 'core.user.update',
    'core.role_assignment.view', 'core.role_assignment.create', 'core.role_assignment.delete',
    'core.company_group.view', 'core.company_group.update',
    'core.legal_entity.view', 'core.legal_entity.create', 'core.legal_entity.update',
    'core.location.view', 'core.location.create', 'core.location.update',
    'core.org_unit.view', 'core.org_unit.create', 'core.org_unit.update', 'core.org_unit_type.update',
    'core.job_catalogue.view', 'core.job_catalogue.update',
    'core.cost_centre.view', 'core.cost_centre.create', 'core.cost_centre.update',
    'core.employee_category.view', 'core.employee_category.update',
    'core.workflow.view', 'core.workflow.reassign', 'core.workflow.admin',
    'core.workflow_definition.view', 'core.workflow_definition.manage',
    'core.notification.admin', 'core.notification_template.view',
    'core.setting.view', 'core.setting.update', 'core.sequence.view', 'core.sequence.update',
    'core.audit.view', 'core.audit.view_sensitive',
    'core.document_template.view', 'core.document_template.manage',
    'core.import.view', 'core.import.run', 'core.support_session.view',
    // People (backend PeoplePermissions)
    'hr.employee.view', 'hr.employee.export', 'hr.person.create', 'hr.person.update', 'hr.person.view_sensitive',
    'hr.person.merge', 'hr.employment.create', 'hr.employment.update', 'hr.assignment.update',
    'hr.compensation.view', 'hr.compensation.update', 'hr.contract.view', 'hr.contract.create', 'hr.contract.update',
    'hr.decision.view', 'hr.decision.create', 'hr.decision.update', 'hr.dependant.view', 'hr.dependant.update',
    'hr.bank_account.view', 'hr.bank_account.update', 'hr.tax_residency.update', 'hr.special_status.view',
    'hr.special_status.update', 'hr.work_permit.update', 'hr.separation.view', 'hr.separation.create',
    'hr.separation.update', 'hr.statutory_report.view', 'hr.statutory_report.execute', 'hr.import.run',
    'hr.dsr.execute', 'hr.ess.view', 'hr.ess.request',
  ].map((code) => ({ code, scopes: [{ scopeType: 'TENANT' as const, scopeIds: [], includeSubtree: true, fieldMaskLevel: 'NONE' }] })),
};

/** Same user with a reduced permission set. */
export function meWith(...codes: string[]): Me {
  return { ...meFixture, permissions: meFixture.permissions.filter((p) => codes.includes(p.code)) };
}
