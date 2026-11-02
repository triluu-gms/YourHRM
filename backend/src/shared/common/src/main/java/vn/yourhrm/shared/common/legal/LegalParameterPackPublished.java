package vn.yourhrm.shared.common.legal;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * {@code saas.LegalParameterPackPublished} (DESIGN.md §6 rule 3): a new legal pack became current. Packs are global;
 * the event bus delivers it to each tenant's consumers inside that tenant's {@code TenantContext}.
 *
 * @param packVersion         the new current pack version
 * @param previousPackVersion the pack it supersedes
 * @param changedKeys         parameter keys whose values differ between the two packs
 * @param changedFrom         dates from which values changed (retroactive dates included, NFR-SAAS-016)
 */
public record LegalParameterPackPublished(long packVersion, long previousPackVersion, Set<String> changedKeys,
        List<LocalDate> changedFrom) {

    public LegalParameterPackPublished {
        changedKeys = Set.copyOf(changedKeys);
        changedFrom = List.copyOf(changedFrom);
    }
}
