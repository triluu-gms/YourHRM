/**
 * People module (BRD 02-people, schema {@code hr}): the system of record for persons and their PII, employments
 * (concurrent across legal entities), date-effective assignments and reporting lines, agreed compensation, labour
 * contracts, decisions, dependants, bank accounts, separation and the statutory labour reports. Connects with its own
 * pool and login role {@code yhrm_app_hr} (ADR-0021). Only {@code vn.yourhrm.people.api} is visible to other modules.
 */
@ApplicationModule(displayName = "People")
package vn.yourhrm.people;

import org.springframework.modulith.ApplicationModule;
