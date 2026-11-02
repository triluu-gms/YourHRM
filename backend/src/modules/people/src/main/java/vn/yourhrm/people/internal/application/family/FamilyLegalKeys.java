package vn.yourhrm.people.internal.application.family;

/**
 * Legal-parameter keys of the family / PIT dependant stream (BR-PPL-016/017, C17; all {@code [verify-legal]}, values
 * live in the Legal Parameter Service, never in code).
 */
public final class FamilyLegalKeys {

    private FamilyLegalKeys() {
    }

    /**
     * Map relationship category → list of {@code RELATIONSHIP} code-list values (Circular 111/2013 Art. 9.1.đ: who can
     * be a dependant), e.g. {@code {"CHILD":["CHILD","ADOPTED_CHILD","STEP_CHILD"],"SPOUSE":["SPOUSE"],
     * "PARENT":["FATHER","MOTHER","FATHER_IN_LAW","MOTHER_IN_LAW"],"OTHER":["SIBLING","GRANDPARENT"]}}.
     * A relationship that is in no category cannot be a PIT dependant.
     */
    public static final String RELATIONSHIP_CATEGORIES = "dependant.relationship.categories";

    /** Age in years until which a child is a dependant without further conditions (example 18; the month of the
     * birthday still counts). */
    public static final String CHILD_MAX_AGE = "dependant.child.max_age";

    /** Average monthly income (VND) above which a family member is not a dependant (example 1,000,000). */
    public static final String INCOME_MONTHLY_THRESHOLD = "dependant.income.monthly_threshold";

    /** Code of the dependant registration form printed on the list (example "07/ĐK-NPT-TNCN"; high uncertainty). */
    public static final String REGISTRATION_FORM_CODE = "dependant.registration.form_code";
}
