package vn.yourhrm.core.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Global master data for other modules (BR-CORE-080..082; read-only for tenants): administrative units with the
 * old→new resolver, and code-list lookups (global values plus tenant overrides).
 */
public interface MasterData {

    /** An administrative unit and its validity. {@code level}: PROVINCE, DISTRICT (pre-2025 only) or COMMUNE. */
    record AdminUnit(String code, String level, String nameVi, String nameEn, String parentCode, LocalDate validFrom,
            LocalDate validTo) {
    }

    /**
     * Result of resolving an address unit for a date (BR-CORE-081, US-CORE-081).
     *
     * @param status     CURRENT (valid on the date), MAPPED (one successor), AMBIGUOUS (a split: the user must choose
     *                   among {@code candidates}), UNKNOWN (no such code, or no successor)
     * @param candidates the unit(s) valid on the date the input leads to
     */
    record Resolution(String inputCode, String status, List<AdminUnit> candidates, String note) {
    }

    Optional<AdminUnit> adminUnit(String code);

    Resolution resolveAdminUnit(String code, LocalDate asOf);

    /** Is {@code valueCode} an active value of {@code listCode} for the current tenant on {@code asOf}? */
    boolean isValidCode(String listCode, String valueCode, LocalDate asOf);
}
