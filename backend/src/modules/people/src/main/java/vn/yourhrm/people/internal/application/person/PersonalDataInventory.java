package vn.yourhrm.people.internal.application.person;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * People's personal-data dictionary (BR-PPL-150, US-PPL-083; Decree 13/2023 Art. 2, PDPL 2025 {@code [verify-legal]}):
 * the PII class of every column of schema {@code hr}, whether it is SENSITIVE personal data (health, ethnicity,
 * religion, political / union membership, financial), and the lawful basis of the sensitive ones (consent only where
 * the processing is optional, BR-PPL-151). Source for the SaaS PD inventory registration (BR-SAAS-079, not built yet).
 *
 * <p>Resolution order: explicit entry, then the DDL {@code pii:Px} column comment, then naming rules for technical,
 * reference and encrypted columns. {@code PersonalDataInventoryTest} fails when an {@code hr} column resolves to
 * nothing, so a new personal column cannot ship unclassified.
 */
public final class PersonalDataInventory {

    private PersonalDataInventory() {
    }

    public enum PiiClass {
        P0, P1, P2, P3
    }

    /** Why sensitive data is processed: a statutory duty, the labour contract, or the person's consent. */
    public enum LawfulBasis {
        LEGAL_OBLIGATION, CONTRACT, CONSENT
    }

    /**
     * @param category SENSITIVE category (HEALTH, ETHNICITY, RELIGION, POLITICAL, UNION, FINANCIAL, IDENTIFIER) or a
     *                 basic one (IDENTITY, CONTACT, EMPLOYMENT, PAY, TECHNICAL, REFERENCE)
     * @param origin   EXPLICIT, DDL_COMMENT or RULE
     */
    public record Classification(PiiClass piiClass, boolean sensitive, String category, LawfulBasis lawfulBasis,
            String origin) {
    }

    private static final Map<String, Classification> EXPLICIT = new HashMap<>();
    private static final Pattern COMMENT = Pattern.compile("pii:(P[0-3])");
    private static final Set<String> TECHNICAL = Set.of("tenant_id", "id", "created_at", "created_by", "updated_at",
            "updated_by", "version", "version_no", "status", "source", "valid_from", "valid_to", "valid_during",
            "recorded_at", "superseded_at", "superseded_by", "subject_key_version", "is_anonymised", "anonymised_at",
            "decided_at", "decided_by", "approved_at", "approved_by", "verified_at", "verified_by", "completed_at",
            "completed_by", "generated_at", "generated_by", "submitted_at", "submitted_by", "withdrawn_at");

    static {
        // ---- sensitive P3 (encrypted) with their lawful basis -------------------------------------------------------
        p3("person", "national_id_enc", "IDENTIFIER", LawfulBasis.LEGAL_OBLIGATION);
        p3("person", "tax_code_enc", "IDENTIFIER", LawfulBasis.LEGAL_OBLIGATION);
        p3("person", "si_number_enc", "IDENTIFIER", LawfulBasis.LEGAL_OBLIGATION);
        p3("person", "hi_card_no_enc", "HEALTH", LawfulBasis.LEGAL_OBLIGATION);
        p3("person", "ethnicity_enc", "ETHNICITY", LawfulBasis.LEGAL_OBLIGATION);   // labour book, SI declarations
        p3("person", "religion_enc", "RELIGION", LawfulBasis.CONSENT);               // optional (BR-PPL-010/151)
        p3("person_identity_doc", "doc_no_enc", "IDENTIFIER", LawfulBasis.LEGAL_OBLIGATION);
        p3("family_member", "national_id_enc", "IDENTIFIER", LawfulBasis.LEGAL_OBLIGATION);
        p3("family_member", "tax_code_enc", "IDENTIFIER", LawfulBasis.LEGAL_OBLIGATION);
        p3("family_member", "is_disabled", "HEALTH", LawfulBasis.LEGAL_OBLIGATION);  // dependant eligibility
        p3("bank_account", "account_no_enc", "FINANCIAL", LawfulBasis.CONTRACT);
        // health / union / party details; party and youth-union membership need consent (checked per type)
        p3("special_status", "detail_enc", "HEALTH", LawfulBasis.LEGAL_OBLIGATION);
        p3("special_status", "status_type", "HEALTH", LawfulBasis.LEGAL_OBLIGATION);
        p3("special_status", "is_sensitive", "HEALTH", LawfulBasis.LEGAL_OBLIGATION);
        p3("special_status", "classification_code", "HEALTH", LawfulBasis.LEGAL_OBLIGATION);

        // ---- basic personal data ----------------------------------------------------------------------------------
        basic(PiiClass.P1, "IDENTITY", "person", "family_name", "middle_name", "given_name", "full_name",
                "full_name_search", "latin_name", "preferred_name", "gender_code", "nationality_code",
                "national_id_type", "verification_status");
        basic(PiiClass.P2, "IDENTITY", "person", "birth_date", "hi_facility_code", "si_book_custody");
        basic(PiiClass.P1, "IDENTITY", "person_identity_doc", "doc_type", "issue_date", "expiry_date",
                "issuing_country_code", "visa_type_code");
        basic(PiiClass.P2, "IDENTITY", "person_identity_doc", "issue_place");
        basic(PiiClass.P1, "CONTACT", "person_address", "address_type", "mapping_status", "country_code",
                "province_code", "commune_code", "legacy_province_code", "legacy_district_code", "legacy_commune_code");
        basic(PiiClass.P2, "CONTACT", "person_address", "address_line", "legacy_address_text");
        basic(PiiClass.P1, "CONTACT", "person_contact", "contact_type", "is_primary", "is_verified");
        basic(PiiClass.P2, "CONTACT", "person_contact", "contact_value");
        basic(PiiClass.P2, "TAX", "tax_residency", "status", "basis", "arrival_date", "departure_date", "tax_year",
                "days_in_vn", "proposal_ref", "approval_status");
        basic(PiiClass.P2, "TAX", "vn_presence_period", "entry_date", "exit_date", "presence_period");
        basic(PiiClass.P1, "EDUCATION", "education_qualification", "qualification_level_code",
                "vocational_skill_level_code", "general_education_code", "field_of_study", "institution_name",
                "graduation_year", "is_highest");
        basic(PiiClass.P1, "EMPLOYMENT", "employment", "employee_no", "hire_type", "hire_date", "original_hire_date",
                "seniority_date", "probation_end_date", "end_date", "last_working_date", "employment_period",
                "worker_category_code", "employee_category_code", "working_regime_code", "is_rehire_eligible");
        basic(PiiClass.P1, "EMPLOYMENT", "primary_employment", "purpose", "reason_code");
        basic(PiiClass.P1, "EMPLOYMENT", "insurance_participation", "insurance_type", "is_participating", "basis_rule",
                "exemption_reason_code", "legal_pack_version", "is_manual_override", "override_reason");
        basic(PiiClass.P1, "EMPLOYMENT", "decision", "decision_no", "decision_type_code", "sign_date",
                "effective_date", "signatory_name", "signatory_title");
        basic(PiiClass.P1, "EMPLOYMENT", "assignment", "assignment_key", "assignment_type", "cost_split", "fte",
                "action_code", "reason_code", "correction_reason");
        basic(PiiClass.P2, "PAY", "salary_record", "pay_basis", "salary_kind", "base_salary_amount",
                "agreed_net_amount", "net_guaranteed_scope", "si_salary_amount", "probation_pct", "correction_reason",
                "currency_code", "pay_scale_code", "pay_scale_step_code", "action_code", "reason_code");
        basic(PiiClass.P2, "PAY", "salary_record_allowance", "amount", "component_code", "is_in_si_salary");
        basic(PiiClass.P1, "EMPLOYMENT", "contract", "contract_no", "definite_seq", "sign_date", "start_date",
                "end_date", "probation_days", "signatory_name", "signatory_title", "esign_status",
                "national_platform_ref", "contract_type_code");
        basic(PiiClass.P1, "EMPLOYMENT", "contract_appendix", "appendix_no", "effective_date", "changes");
        basic(PiiClass.P1, "EMPLOYMENT", "contract_suspension", "return_date");
        basic(PiiClass.P2, "FAMILY", "family_member", "full_name", "birth_date", "has_income_above_threshold",
                "relationship_code", "is_deceased");
        basic(PiiClass.P2, "FAMILY", "pit_dependant", "from_month", "to_month", "month_range", "tax_registration_ref",
                "dependant_reason_code", "registered_at");
        basic(PiiClass.P2, "CONTACT", "emergency_contact", "full_name", "phone", "alt_phone", "email",
                "address_text", "relationship_code");
        basic(PiiClass.P2, "FINANCIAL", "bank_account", "holder_name", "split_type", "split_value",
                "step_up_auth_ref", "bank_code", "branch_code", "currency_code");
        basic(PiiClass.P1, "EMPLOYMENT", "special_status", "override_reason", "scope_id");
        basic(PiiClass.P2, "EMPLOYMENT", "work_permit", "permit_type", "permit_no", "job_title_on_permit",
                "work_location_text", "issuing_authority", "issue_date");
        basic(PiiClass.P2, "EMPLOYMENT", "separation", "separation_type", "initiated_by", "reason_code",
                "legal_ground_code", "request_date", "notice_date", "required_notice_days", "notice_day_basis",
                "notice_shortfall_days", "last_working_date", "termination_date", "access_revocation_date",
                "severance_type", "severance_eligibility", "is_protected", "protected_override_reason",
                "is_rehire_eligible", "payroll_result_ref", "settlement_due_date");
        basic(PiiClass.P1, "EMPLOYMENT", "separation_service_period", "period_kind", "period_from", "period_to", "note");
        basic(PiiClass.P1, "EMPLOYMENT", "hr_document", "category_code", "title", "confidentiality",
                "retention_class_code", "retention_until", "source_entity");
        basic(PiiClass.P0, "REPORTING", "statutory_report", "report_type", "period_start", "period_end", "due_date",
                "template_version", "row_count", "submission_channel", "submission_ref");
        // Phase-2 shells
        basic(PiiClass.P1, "EMPLOYMENT", "certificate", "name", "issuer", "certificate_no", "issue_date",
                "expiry_date", "certificate_type_code");
        basic(PiiClass.P2, "EMPLOYMENT", "discipline_case", "violation_date", "rule_reference", "description",
                "hearing_date", "sanction_from", "sanction_to", "expunge_date", "damages_amount", "sanction_code");
        basic(PiiClass.P2, "PAY", "reward", "reward_type", "reward_level", "amount", "target_pay_month");
        basic(PiiClass.P2, "EMPLOYMENT", "appraisal", "appraisal_type", "period_start", "period_end", "score",
                "rating_code");
        basic(PiiClass.P1, "EMPLOYMENT", "training_record", "course_name", "provider", "start_date", "end_date",
                "hours_qty", "cost_amount", "bond_end_date", "bond_amount");
        basic(PiiClass.P0, "TECHNICAL", "checklist_template", "event_type", "name", "task_definitions");
        basic(PiiClass.P0, "TECHNICAL", "checklist", "event_type");
        basic(PiiClass.P1, "EMPLOYMENT", "checklist_task", "title", "due_date", "requires_attachment", "seq");
    }

    private static void p3(String table, String column, String category, LawfulBasis basis) {
        EXPLICIT.put(table + "." + column, new Classification(PiiClass.P3, true, category, basis, "EXPLICIT"));
    }

    private static void basic(PiiClass c, String category, String table, String... columns) {
        for (String col : columns) {
            EXPLICIT.putIfAbsent(table + "." + col, new Classification(c, false, category, null, "EXPLICIT"));
        }
    }

    /**
     * The classification of {@code hr.<table>.<column>}; {@code ddlComment} is the column comment (may be null).
     * Empty = unclassified (the inventory test fails).
     */
    public static Optional<Classification> classify(String table, String column, String ddlComment) {
        Classification explicit = EXPLICIT.get(table + "." + column);
        if (explicit != null) {
            return Optional.of(explicit);
        }
        if (ddlComment != null) {
            Matcher m = COMMENT.matcher(ddlComment);
            if (m.find()) {
                PiiClass c = PiiClass.valueOf(m.group(1));
                boolean sensitive = c == PiiClass.P3 || ddlComment.contains("sensitive");
                return Optional.of(new Classification(c, sensitive, sensitive ? "SENSITIVE" : "BASIC",
                        sensitive ? LawfulBasis.LEGAL_OBLIGATION : null, "DDL_COMMENT"));
            }
        }
        if (TECHNICAL.contains(column)) {
            return Optional.of(new Classification(PiiClass.P0, false, "TECHNICAL", null, "RULE"));
        }
        if (column.endsWith("_bidx")) {
            // keyed hash of a P3 identifier: pseudonymous, never leaves the module (D6, 007_publications)
            return Optional.of(new Classification(PiiClass.P3, false, "IDENTIFIER", null, "RULE"));
        }
        if (column.endsWith("_last4")) {
            return Optional.of(new Classification(PiiClass.P2, false, "IDENTIFIER", null, "RULE"));
        }
        if (column.endsWith("_id") || column.endsWith("_by_id") || column.endsWith("_of_id")) {
            return Optional.of(new Classification(PiiClass.P1, false, "REFERENCE", null, "RULE"));
        }
        return Optional.empty();
    }

    /** Explicit entries (for the SaaS PD-inventory registration). */
    public static Map<String, Classification> explicitEntries() {
        return Map.copyOf(EXPLICIT);
    }
}
