package vn.yourhrm.core.internal.domain;

import java.util.Set;

/** Codes of the system roles seeded in every tenant (US-CORE-033). */
public final class SystemRoleCodes {

    public static final String TENANT_OWNER = "TENANT_OWNER";
    public static final String HR_ADMIN = "HR_ADMIN";
    public static final String ESS = "ESS";
    public static final String LINE_MANAGER = "LINE_MANAGER";
    public static final String FORMER_EMPLOYEE = "FORMER_EMPLOYEE";
    public static final String AUDITOR = "AUDITOR";

    /**
     * Roles held only through derivation (BR-CORE-033): LINE_MANAGER from org-unit management, ESS / FORMER_EMPLOYEE
     * (scope SELF) from the user's own employment link and status. They can never be granted by hand.
     */
    public static final Set<String> DERIVED = Set.of(LINE_MANAGER, ESS, FORMER_EMPLOYEE);

    private SystemRoleCodes() {
    }
}
