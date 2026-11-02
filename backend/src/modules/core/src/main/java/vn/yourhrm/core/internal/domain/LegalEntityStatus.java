package vn.yourhrm.core.internal.domain;

/** Lifecycle of a legal entity ({@code core.legal_entity.status}). DRAFT and ACTIVE count towards the plan limit. */
public enum LegalEntityStatus {
    DRAFT,
    ACTIVE,
    DISSOLVED
}
