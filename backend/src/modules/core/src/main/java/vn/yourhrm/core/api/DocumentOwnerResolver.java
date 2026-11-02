package vn.yourhrm.core.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;

/**
 * Implemented by the module owning a record kind (BR-CORE-101): turns a record into the authorisation target Core
 * checks the document type's permission against, e.g. a contract into its {@link AuthorizationTarget.Employee}.
 * Without a resolver Core uses the document's legal entity, or the tenant.
 */
public interface DocumentOwnerResolver {

    /** The owner entity types this resolver answers for, e.g. {@code hr.contract}. */
    Set<String> ownerEntityTypes();

    /** The target of the record, or empty when it does not exist (access is then refused). */
    Optional<AuthorizationTarget> target(String ownerEntityType, UUID ownerEntityId);
}
