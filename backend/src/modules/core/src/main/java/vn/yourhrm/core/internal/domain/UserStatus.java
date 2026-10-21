package vn.yourhrm.core.internal.domain;

/** {@code core.user_account.status} (CHECK user_account_status_ck). */
public enum UserStatus {
    INVITED, ACTIVE, FORMER_EMPLOYEE, DEACTIVATED, LOCKED;

    /** Statuses that may sign in (FORMER_EMPLOYEE keeps limited access through its roles, ADR-0005 §7). */
    public boolean mayAccess() {
        return this == ACTIVE || this == FORMER_EMPLOYEE;
    }
}
