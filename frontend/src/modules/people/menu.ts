import type { MenuEntry } from '@/shared/navigation';
import { PeoplePermissions as P } from './permissions';

/**
 * People's contribution to the admin menu. Labels are `common:nav.people.*` (added by `./i18n`); paths are owned by
 * `areas/<area>/routes.tsx` — keep both in step.
 */
export const peopleMenu: MenuEntry[] = [
  {
    key: 'people',
    labelKey: 'nav.people.group',
    icon: 'team',
    children: [
      { key: 'people-employees', labelKey: 'nav.people.employees', path: '/people/employees', permissions: [P.EMPLOYEE_VIEW] },
      { key: 'people-hire', labelKey: 'nav.people.hire', path: '/people/hires/new', permissions: [P.EMPLOYMENT_CREATE] },
      { key: 'people-salary-records', labelKey: 'nav.people.salaryRecords', path: '/people/salary-records', permissions: [P.COMPENSATION_VIEW] },
      { key: 'people-separations', labelKey: 'nav.people.separations', path: '/people/separations', permissions: [P.SEPARATION_VIEW] },
    ],
  },
  {
    key: 'people-contracts',
    labelKey: 'nav.people.contractsGroup',
    icon: 'solution',
    children: [
      { key: 'people-contracts-expiring', labelKey: 'nav.people.contractsExpiring', path: '/people/contracts/expiring', permissions: [P.CONTRACT_VIEW] },
      { key: 'people-probations-ending', labelKey: 'nav.people.probationsEnding', path: '/people/contracts/probations-ending', permissions: [P.CONTRACT_VIEW] },
    ],
  },
  {
    key: 'people-compliance',
    labelKey: 'nav.people.complianceGroup',
    icon: 'idcard',
    children: [
      { key: 'people-expiring-documents', labelKey: 'nav.people.expiringDocuments', path: '/people/expiring-documents', permissions: [P.EMPLOYEE_VIEW] },
      { key: 'people-residency-proposals', labelKey: 'nav.people.residencyProposals', path: '/people/residency-proposals', permissions: [P.TAX_RESIDENCY_UPDATE] },
    ],
  },
  {
    key: 'people-reports',
    labelKey: 'nav.people.reportsGroup',
    icon: 'chart',
    children: [
      { key: 'people-statutory-reports', labelKey: 'nav.people.statutoryReports', path: '/people/statutory-reports', permissions: [P.STATUTORY_REPORT_VIEW] },
      { key: 'people-labour-book', labelKey: 'nav.people.labourBook', path: '/people/reports/labour-book', permissions: [P.STATUTORY_REPORT_VIEW] },
      { key: 'people-dependant-registrations', labelKey: 'nav.people.dependantRegistrations', path: '/people/reports/dependant-registrations', permissions: [P.STATUTORY_REPORT_VIEW] },
      { key: 'people-concurrent-employment', labelKey: 'nav.people.concurrentEmployment', path: '/people/reports/concurrent-employment', permissions: [P.EMPLOYEE_VIEW] },
      { key: 'people-opening-balance', labelKey: 'nav.people.openingBalance', path: '/people/opening-balance', permissions: [P.IMPORT_RUN] },
      { key: 'people-integration-inbox', labelKey: 'nav.people.integrationInbox', path: '/people/integration-inbox', permissions: [P.IMPORT_RUN] },
    ],
  },
  {
    key: 'people-ess',
    labelKey: 'nav.people.essGroup',
    icon: 'user',
    children: [
      { key: 'people-me', labelKey: 'nav.people.myProfile', path: '/people/me', permissions: [P.ESS_VIEW] },
      { key: 'people-me-family', labelKey: 'nav.people.myFamily', path: '/people/me/family', permissions: [P.ESS_VIEW] },
      { key: 'people-me-bank', labelKey: 'nav.people.myBankAccounts', path: '/people/me/bank-accounts', permissions: [P.ESS_VIEW] },
      { key: 'people-me-documents', labelKey: 'nav.people.myDocuments', path: '/people/me/documents', permissions: [P.CONTRACT_VIEW] },
      { key: 'people-me-resignation', labelKey: 'nav.people.myResignation', path: '/people/me/resignation', permissions: [P.ESS_VIEW] },
    ],
  },
];
