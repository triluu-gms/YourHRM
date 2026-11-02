package vn.yourhrm.core.internal.domain;

import java.util.regex.Pattern;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Enterprise tax code (MST) of a legal entity (BR-CORE-003, VN-03 [verify-legal]): 10 digits, or 13 written as
 * {@code NNNNNNNNNN-NNN} for a dependent unit. Same rule as the DDL check {@code legal_entity_version_tax_code_ck};
 * validated here first so the client gets a readable error instead of a constraint violation.
 */
public record TaxCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[0-9]{10}(-[0-9]{3})?$");

    public TaxCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw ApiException.unprocessable("invalid-tax-code",
                    "Tax code (MST) must be 10 digits or NNNNNNNNNN-NNN, got: " + value);
        }
    }

    /** Parses user input: surrounding blanks are trimmed, nothing else is normalised. */
    public static TaxCode parse(String raw) {
        return new TaxCode(raw == null ? null : raw.strip());
    }

    /** True for a 13-digit code of a dependent unit (chi nhánh / đơn vị phụ thuộc). */
    public boolean isDependentUnit() {
        return value.length() == 14;
    }
}
