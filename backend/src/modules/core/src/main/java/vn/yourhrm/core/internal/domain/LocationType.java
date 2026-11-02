package vn.yourhrm.core.internal.domain;

/** Kind of work location (BR-CORE-005; DDL {@code location_version_type_ck}). */
public enum LocationType {
    HEAD_OFFICE,
    BRANCH,
    REPRESENTATIVE_OFFICE,
    FACTORY,
    STORE,
    CONSTRUCTION_SITE,
    REMOTE
}
