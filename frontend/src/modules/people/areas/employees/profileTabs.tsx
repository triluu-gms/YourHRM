import type { ComponentType } from 'react';
import type { ProfileTabKey, ProfileTabProps } from '../../common';
import { AddressesTab, ContactsTab, IdentityDocumentsTab, PersonTab, QualificationsTab } from '../person/tabs';
import { AssignmentsTab, CompensationTab, EmploymentTab } from '../employment/tabs';
import { ContractsTab, DecisionsTab } from '../contracts/tabs';
import { BankAccountsTab, DependantsTab, FamilyMembersTab } from '../family/tabs';
import { SpecialStatusesTab, TaxResidencyTab, WorkPermitsTab } from '../compliance/tabs';
import { SeparationTab } from '../separation/tabs';

/** Pseudo-tab always shown first (employment facts, status, primary employment, insurance participation). */
export type ShellTabKey = 'employment' | ProfileTabKey;

/**
 * Display order of the employee-profile tabs and the area component rendering each. The backend decides which of
 * the `ProfileTabKey`s the caller may open (`EmployeeProfile.tabs`); unknown keys are ignored.
 */
export const PROFILE_TABS: { key: ShellTabKey; component: ComponentType<ProfileTabProps> }[] = [
  { key: 'employment', component: EmploymentTab },
  { key: 'person', component: PersonTab },
  { key: 'assignments', component: AssignmentsTab },
  { key: 'contracts', component: ContractsTab },
  { key: 'decisions', component: DecisionsTab },
  { key: 'compensation', component: CompensationTab },
  { key: 'addresses', component: AddressesTab },
  { key: 'contacts', component: ContactsTab },
  { key: 'identityDocuments', component: IdentityDocumentsTab },
  { key: 'qualifications', component: QualificationsTab },
  { key: 'familyMembers', component: FamilyMembersTab },
  { key: 'dependants', component: DependantsTab },
  { key: 'bankAccounts', component: BankAccountsTab },
  { key: 'taxResidency', component: TaxResidencyTab },
  { key: 'workPermits', component: WorkPermitsTab },
  { key: 'specialStatuses', component: SpecialStatusesTab },
  { key: 'separation', component: SeparationTab },
];
