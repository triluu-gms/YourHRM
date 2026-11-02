package vn.yourhrm.shared.auth.api;

/**
 * Field-masking level of the current user for one permission (BR-CORE-034, DESIGN.md §9.4), the value of
 * {@code app.mask_level} that the masking views read: {@code NONE} shows everything, {@code PII} hides personal
 * data, {@code PII_AND_PAY} also hides pay. Ordered from least to most restrictive.
 */
public enum MaskLevel {
    NONE, PII, PII_AND_PAY;

    /** The less restrictive of two levels (effective level = union of grants, BR-CORE-032). */
    public MaskLevel leastRestrictive(MaskLevel other) {
        return ordinal() <= other.ordinal() ? this : other;
    }

    public boolean showsPii() {
        return this == NONE;
    }

    public boolean showsPay() {
        return this != PII_AND_PAY;
    }
}
