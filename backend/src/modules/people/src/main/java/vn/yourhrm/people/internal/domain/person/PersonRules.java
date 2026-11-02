package vn.yourhrm.people.internal.domain.person;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.text.Normalizer;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Format rules of personal identifiers and contact values (BR-PPL-001/010/013/014, US-PPL-002/006/008/009). Values
 * are normalised ({@link #normalise}: NFKC, no spaces, dots or dashes, upper case; the same normal form as the blind index) before they are checked,
 * encrypted and blind-indexed. Error messages never echo the value (P3 never reaches logs or responses).
 */
public final class PersonRules {

    private PersonRules() {
    }

    public static final Set<String> NATIONAL_ID_TYPES = Set.of("CCCD", "CMND", "PASSPORT");
    public static final Set<String> SI_BOOK_CUSTODY = Set.of("EMPLOYER", "EMPLOYEE", "RETURNED");

    private static final Pattern CCCD = Pattern.compile("^\\d{12}$");
    private static final Pattern CMND = Pattern.compile("^(\\d{9}|\\d{12})$");
    private static final Pattern PASSPORT = Pattern.compile("^[A-Z0-9]{6,12}$");
    /** 12-digit personal identification number (Circular 86/2024) or the legacy 10-digit personal MST. */
    private static final Pattern TAX_CODE = Pattern.compile("^(\\d{10}|\\d{12})$");
    private static final Pattern LEGACY_TAX_CODE = Pattern.compile("^\\d{10}$");
    /** Mã số BHXH: 10 digits (US-PPL-009). */
    private static final Pattern SI_NUMBER = Pattern.compile("^\\d{10}$");
    /** HI card: the 10-digit SI number (2025 cards) or the older 15-character card number. */
    private static final Pattern HI_CARD = Pattern.compile("^([A-Z]{2}\\d{13}|\\d{10})$");
    private static final Pattern DOC_NO = Pattern.compile("^[A-Z0-9]{4,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?\\d{8,15}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    /** The national identifier in normal form, checked against its type. */
    public static String nationalId(String type, String value) {
        if (!NATIONAL_ID_TYPES.contains(type)) {
            throw ApiException.unprocessable("invalid-national-id-type", "National id type must be CCCD, CMND or PASSPORT");
        }
        String n = normalise(value);
        Pattern p = switch (type) {
            case "CCCD" -> CCCD;
            case "CMND" -> CMND;
            default -> PASSPORT;
        };
        if (!p.matcher(n).matches()) {
            throw ApiException.unprocessable("invalid-national-id", switch (type) {
                case "CCCD" -> "A CCCD / personal identification number has 12 digits";
                case "CMND" -> "A CMND number has 9 or 12 digits";
                default -> "A passport number has 6 to 12 letters or digits";
            });
        }
        return n;
    }

    /**
     * Value hashed into {@code national_id_bidx}: the number, and for a passport the nationality too, so that the
     * uniqueness of BR-PPL-001 is "CCCD" or "passport + nationality".
     */
    public static String nationalIdKey(String type, String normalisedNumber, String nationalityCode) {
        return "PASSPORT".equals(type) ? country(nationalityCode) + ":" + normalisedNumber : normalisedNumber;
    }

    public static String taxCode(String value) {
        String n = normalise(value);
        if (!TAX_CODE.matcher(n).matches()) {
            throw ApiException.unprocessable("invalid-tax-code",
                    "A personal tax code is the 12-digit personal identification number or a 10-digit MST");
        }
        return n;
    }

    public static boolean isLegacyTaxCode(String normalised) {
        return normalised != null && LEGACY_TAX_CODE.matcher(normalised).matches();
    }

    public static String legacyTaxCode(String value) {
        String n = normalise(value);
        if (!isLegacyTaxCode(n)) {
            throw ApiException.unprocessable("invalid-legacy-tax-code", "A legacy personal MST has 10 digits");
        }
        return n;
    }

    public static String siNumber(String value) {
        String n = normalise(value);
        if (!SI_NUMBER.matcher(n).matches()) {
            throw ApiException.unprocessable("invalid-si-number", "A social-insurance number (mã số BHXH) has 10 digits");
        }
        return n;
    }

    public static String hiCardNo(String value) {
        String n = normalise(value);
        if (!HI_CARD.matcher(n).matches()) {
            throw ApiException.unprocessable("invalid-hi-card-no",
                    "A health-insurance card number is 2 letters + 13 digits, or the 10-digit SI number");
        }
        return n;
    }

    public static String documentNo(String value) {
        String n = normalise(value);
        if (!DOC_NO.matcher(n).matches()) {
            throw ApiException.unprocessable("invalid-document-no", "A document number has 4 to 20 letters or digits");
        }
        return n;
    }

    public static String country(String code) {
        String c = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
        if (c == null || !COUNTRY.matcher(c).matches()) {
            throw ApiException.unprocessable("invalid-country-code", "A country code has two letters (ISO 3166-1)");
        }
        return c;
    }

    /** Contact value in normal form, checked against the contact type (US-PPL-006). */
    public static String contactValue(String contactType, String value) {
        String v = value == null ? "" : value.trim();
        switch (contactType) {
            case "PERSONAL_EMAIL", "WORK_EMAIL" -> {
                if (!EMAIL.matcher(v).matches() || v.length() > 254) {
                    throw ApiException.unprocessable("invalid-email", "Not a valid e-mail address");
                }
                return v.toLowerCase(Locale.ROOT);
            }
            case "MOBILE", "HOME_PHONE", "WORK_PHONE" -> {
                String p = v.replaceAll("[\\s.()\\-]", "");
                if (!PHONE.matcher(p).matches()) {
                    throw ApiException.unprocessable("invalid-phone", "A phone number has 8 to 15 digits, optionally +");
                }
                return p;
            }
            case "OTHER" -> {
                if (v.isEmpty() || v.length() > 200) {
                    throw ApiException.unprocessable("invalid-contact", "A contact value is 1 to 200 characters");
                }
                return v;
            }
            default -> throw ApiException.unprocessable("invalid-contact-type", "Unknown contact type " + contactType);
        }
    }

    /** Identifier normal form (same as {@code PiiCipher.normalise}). */
    public static String normalise(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).replaceAll("[\\s.\\-]", "");
    }

    /** "Họ tên đệm tên", the Vietnamese order (family, middle, given). */
    public static String fullName(String familyName, String middleName, String givenName) {
        StringBuilder sb = new StringBuilder(familyName.trim());
        if (middleName != null && !middleName.isBlank()) {
            sb.append(' ').append(middleName.trim());
        }
        return sb.append(' ').append(givenName.trim()).toString().replaceAll("\\s+", " ");
    }
}
