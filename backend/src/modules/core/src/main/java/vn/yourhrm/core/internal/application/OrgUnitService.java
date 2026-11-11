package vn.yourhrm.core.internal.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.CoreEvents.OrgUnitChanged;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.OrgUnitAssignments;
import vn.yourhrm.core.internal.infra.EffectiveDatedVersions;
import vn.yourhrm.core.internal.infra.EffectiveDatedVersions.Removal;
import vn.yourhrm.core.internal.infra.EffectiveDatedVersions.Slot;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.auth.api.ScopePredicate.SqlFragment;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The effective-dated org tree (BR-CORE-010..012, US-CORE-011..013). P1 changes are direct effective-dated edits
 * (rename, move, change manager / cost centre / location, close); reorg packages are P2 (BR-CORE-013).
 *
 * <p>Every version carries the materialised {@code path} (ltree of unit ids root..self, DESIGN.md §5.3) that data
 * scope and subtree queries rely on. Invariant kept after every write ({@link #normalisePaths}): a version's path is
 * its parent's path on the version's start date plus its own label, and the parent's path is constant over the
 * version's period. When an ancestor moves from date D, descendants' versions are split at D and re-pathed, so the
 * history of a unit also records when its ancestry changed.
 *
 * <p>All org-tree writes of a tenant are serialised (advisory lock): path maintenance spans many units.
 */
@Service
public class OrgUnitService {

    private static final ScopePredicate.Columns SCOPE_COLUMNS =
            new ScopePredicate.Columns("v.legal_entity_id", "v.org_unit_id", "v.location_id", null);
    private static final int MAX_NORMALISE_PASSES = 200;

    private final JdbcClient jdbc;
    private final AuthorizationService authz;
    private final EffectiveDatedVersions versions;
    private final OrgUnitAssignments assignments;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;
    private final BusinessClock clock;

    public OrgUnitService(JdbcClient jdbc, AuthorizationService authz, EffectiveDatedVersions versions,
            ObjectProvider<OrgUnitAssignments> assignments, ApplicationEventPublisher events, AuditLog audit,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.versions = versions;
        // People implements OrgUnitAssignments; until then the documented stub (no assignments) applies.
        this.assignments = assignments.getIfAvailable(() -> OrgUnitAssignments.NONE);
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** The full attribute set of one version (PUT semantics). {@code parentId} null = root of its legal entity. */
    public record Attributes(String nameVi, String nameEn, UUID typeId, UUID parentId, UUID legalEntityId,
            UUID locationId, UUID costCentreId, UUID managerUserId, Integer sortOrder) {
    }

    /**
     * One version of a unit plus display names of what it references (as of the requested date, or of the version's
     * start for the history), so clients need no extra lookups. {@code version} is the unit's optimistic-lock token
     * (anchor, whole history): send it back with every change, close or removal.
     */
    public record OrgUnitView(UUID id, String code, UUID versionId, LocalDate validFrom, LocalDate validTo,
            String nameVi, String nameEn, UUID typeId, String typeCode, String typeNameVi, String typeNameEn,
            UUID parentId, UUID legalEntityId, String legalEntityCode, String legalEntityName,
            UUID locationId, String locationCode, String locationName,
            UUID costCentreId, String costCentreCode, String costCentreName,
            UUID managerUserId, String managerName, int sortOrder, String status, int depth, int version) {
    }

    /** Minimal picker entry (id + code + display name): nothing else leaves the server. */
    public record LookupItem(UUID id, String code, String name) {
    }

    // ------------------------------------------------------------------------------------------------ reads

    /**
     * "Org tree as of D" (BR-CORE-012, US-CORE-013): the units in effect on {@code asOf} and in the caller's scope,
     * optionally the subtree under {@code rootId}, in path order (parents before children). Clients build the tree
     * from {@code parentId}; a scoped user may get subtrees without their ancestors.
     */
    @Transactional(readOnly = true)
    public List<OrgUnitView> tree(LocalDate asOf, UUID rootId, boolean includeClosed, String query) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        UUID tenant = TenantContext.current().tenantId();
        String rootPath = null;
        if (rootId != null) {
            rootPath = pathAt(tenant, rootId, date).orElseThrow(() -> ApiException.notFound("Org unit", rootId));
        }
        String q = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        SqlFragment scope = authz.scopeFor(CorePermissions.ORG_UNIT_VIEW, date).toSql(SCOPE_COLUMNS, "s");
        return jdbc.sql(SELECT + """
                         where v.tenant_id = :t and v.valid_during @> cast(:d as date)
                           and (:closed or v.status = 'ACTIVE')
                           and (cast(:root as text) is null or v.path <@ cast(:root as ltree))
                           and (cast(:q as text) is null or lower(u.code) like :q or lower(v.name_vi) like :q
                                or lower(coalesce(v.name_en, '')) like :q)
                           and\s""" + scope.sql() + " order by v.path")
                .param("t", tenant).param("d", date).param("nd", date).param("closed", includeClosed).param("root", rootPath)
                .param("q", q).params(scope.params())
                .query((rs, n) -> map(rs)).list();
    }

    @Transactional(readOnly = true)
    public OrgUnitView get(UUID id, LocalDate asOf) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        assertCan(CorePermissions.ORG_UNIT_VIEW, id, date);
        return find(id, date).orElseThrow(() -> ApiException.unprocessable("no-version-at-date",
                "Org unit " + id + " is not in effect on " + date));
    }

    /** "History of unit X" (BR-CORE-012): every version, oldest first. */
    @Transactional(readOnly = true)
    public List<OrgUnitView> history(UUID id) {
        assertCan(CorePermissions.ORG_UNIT_VIEW, id, clock.today());
        List<OrgUnitView> history = jdbc.sql(SELECT + " where v.tenant_id = :t and v.org_unit_id = :id order by v.valid_from")
                .param("t", TenantContext.current().tenantId()).param("id", id).param("nd", null)
                .query((rs, n) -> map(rs)).list();
        if (history.isEmpty()) {
            throw ApiException.notFound("Org unit", id);
        }
        return history;
    }

    /** Ancestors of a unit on {@code asOf}, root first (only those in the caller's scope). */
    @Transactional(readOnly = true)
    public List<OrgUnitView> ancestors(UUID id, LocalDate asOf) {
        return related(id, asOf, "v.path @> cast(:p as ltree)");
    }

    /** All descendants of a unit on {@code asOf}, in path order (only those in the caller's scope). */
    @Transactional(readOnly = true)
    public List<OrgUnitView> descendants(UUID id, LocalDate asOf) {
        return related(id, asOf, "v.path <@ cast(:p as ltree)");
    }

    private List<OrgUnitView> related(UUID id, LocalDate asOf, String pathCondition) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        assertCan(CorePermissions.ORG_UNIT_VIEW, id, date);
        UUID tenant = TenantContext.current().tenantId();
        String path = pathAt(tenant, id, date).orElseThrow(() -> ApiException.unprocessable("no-version-at-date",
                "Org unit " + id + " is not in effect on " + date));
        SqlFragment scope = authz.scopeFor(CorePermissions.ORG_UNIT_VIEW, date).toSql(SCOPE_COLUMNS, "s");
        return jdbc.sql(SELECT + " where v.tenant_id = :t and v.valid_during @> cast(:d as date) and v.org_unit_id <> :id"
                        + " and " + pathCondition + " and " + scope.sql() + " order by v.path")
                .param("t", tenant).param("d", date).param("nd", date).param("id", id).param("p", path)
                .params(scope.params())
                .query((rs, n) -> map(rs)).list();
    }

    // ------------------------------------------------------------------------------------------------ writes

    @Transactional
    public OrgUnitView create(String code, LocalDate validFrom, Attributes a) {
        TenantContext ctx = TenantContext.current();
        UUID tenant = ctx.tenantId();
        LocalDate from = validFrom == null ? clock.today() : validFrom;
        // Create under a parent = permission on the parent's subtree; a root = permission on the legal entity.
        AuthorizationTarget target = a.parentId() != null ? new AuthorizationTarget.OrgUnit(a.parentId())
                : new AuthorizationTarget.LegalEntity(a.legalEntityId());
        if (!authz.can(CorePermissions.ORG_UNIT_CREATE, target, from)) {
            throw ApiException.notFound(a.parentId() != null ? "Org unit" : "Legal entity",
                    a.parentId() != null ? a.parentId() : a.legalEntityId());
        }
        lockTenantTree(tenant);
        if (jdbc.sql("select exists (select 1 from core.org_unit where tenant_id = :t and code = :c)")
                .param("t", tenant).param("c", code).query(Boolean.class).single()) {
            throw ApiException.conflict("org-unit-code-taken", "An org unit with code " + code + " already exists");
        }
        UUID id = Ids.newId();
        validate(tenant, id, from, null, a);
        jdbc.sql("insert into core.org_unit (tenant_id, id, code, created_by) values (:t, :id, :code, :actor)")
                .param("t", tenant).param("id", id).param("code", code).param("actor", ctx.userAccountId())
                .update();
        insertVersion(tenant, id, from, null, "ACTIVE", a);
        Set<UUID> moved = normalisePaths(tenant);
        events.publishEvent(new OrgUnitChanged(tenant, id, "CREATED", from, Set.copyOf(moved)));
        return find(id, from).orElseThrow();
    }

    /**
     * Applies {@code a} from {@code effectiveFrom} (BR-CORE-012, US-CORE-012): corrects the version starting then,
     * or ends the covering version at D-1 and starts a new one that keeps the old end, leaving later versions as they
     * are. Moving a unit re-paths its whole subtree from D.
     */
    @Transactional
    public OrgUnitView changeFrom(UUID id, LocalDate effectiveFrom, Attributes a, Integer expectedVersion) {
        UUID tenant = TenantContext.current().tenantId();
        assertCan(CorePermissions.ORG_UNIT_UPDATE, id, effectiveFrom);
        lockTenantTree(tenant);
        versions.checkAndBump(EffectiveDatedVersions.ORG_UNIT, tenant, id, expectedVersion,
                TenantContext.current().userAccountId(), "Org unit " + id);
        Slot slot = versions.slotFor(EffectiveDatedVersions.ORG_UNIT, tenant, id, effectiveFrom);
        OrgUnitView before = find(id, effectiveFrom).orElseThrow();
        if (!Objects.equals(before.parentId(), a.parentId()) && a.parentId() != null
                && !authz.can(CorePermissions.ORG_UNIT_UPDATE, new AuthorizationTarget.OrgUnit(a.parentId()), effectiveFrom)) {
            throw ApiException.notFound("Org unit", a.parentId()); // may not move a unit into a subtree out of scope
        }
        validate(tenant, id, effectiveFrom, slot.covering().validTo(), a);
        if (slot.split()) {
            versions.closeAt(EffectiveDatedVersions.ORG_UNIT, tenant, slot.covering().id(), effectiveFrom);
            insertVersion(tenant, id, effectiveFrom, slot.covering().validTo(), before.status(), a);
        } else {
            Map<String, Object> p = attributeParams(tenant, a);
            p.put("vid", slot.covering().id());
            jdbc.sql("""
                            update core.org_unit_version
                               set name_vi = :nameVi, name_en = :nameEn, org_unit_type_id = :type, parent_id = :parent,
                                   legal_entity_id = :le, location_id = :loc, cost_centre_id = :cc,
                                   manager_user_id = :mgr, sort_order = :sort, updated_by = :actor,
                                   version = version + 1
                             where tenant_id = :t and id = :vid""")
                    .params(p).update();
        }
        Set<UUID> moved = normalisePaths(tenant);
        bumpOthers(tenant, id, moved);
        String change = Objects.equals(before.parentId(), a.parentId()) ? "CHANGED" : "MOVED";
        events.publishEvent(new OrgUnitChanged(tenant, id, change, effectiveFrom, Set.copyOf(moved)));
        return find(id, effectiveFrom).orElseThrow();
    }

    /**
     * Closes a unit from {@code effectiveFrom} (status CLOSED, US-CORE-012). Refused while it has active child units
     * or People reports active assignments in it on or after that date, and when versions already exist after the
     * one covering that date (conflicting future changes must be removed first).
     */
    @Transactional
    public OrgUnitView close(UUID id, LocalDate effectiveFrom, int expectedVersion) {
        UUID tenant = TenantContext.current().tenantId();
        assertCan(CorePermissions.ORG_UNIT_UPDATE, id, effectiveFrom);
        lockTenantTree(tenant);
        versions.checkAndBump(EffectiveDatedVersions.ORG_UNIT, tenant, id, expectedVersion,
                TenantContext.current().userAccountId(), "Org unit " + id);
        Slot slot = versions.slotFor(EffectiveDatedVersions.ORG_UNIT, tenant, id, effectiveFrom);
        OrgUnitView current = find(id, effectiveFrom).orElseThrow();
        if ("CLOSED".equals(current.status())) {
            throw ApiException.unprocessable("org-unit-already-closed", "Org unit " + current.code() + " is already closed");
        }
        if (slot.covering().validTo() != null) {
            throw ApiException.conflict("future-versions-exist", "Org unit " + current.code()
                    + " has changes planned from " + slot.covering().validTo() + "; close it after them");
        }
        if (jdbc.sql("""
                        select exists (select 1 from core.org_unit_version
                                        where tenant_id = :t and parent_id = :id and status = 'ACTIVE'
                                          and (valid_to is null or valid_to > :d))""")
                .param("t", tenant).param("id", id).param("d", effectiveFrom).query(Boolean.class).single()) {
            throw ApiException.conflict("org-unit-has-children",
                    "Move or close the child units of " + current.code() + " first");
        }
        if (assignments.hasActiveAssignments(id, effectiveFrom)) {
            throw ApiException.conflict("org-unit-has-assignments",
                    "Employees are still assigned to " + current.code() + " on or after " + effectiveFrom);
        }
        if (slot.split()) {
            versions.closeAt(EffectiveDatedVersions.ORG_UNIT, tenant, slot.covering().id(), effectiveFrom);
            copyVersion(tenant, slot.covering().id(), effectiveFrom, null, "CLOSED");
        } else {
            jdbc.sql("""
                            update core.org_unit_version set status = 'CLOSED', updated_by = :actor, version = version + 1
                             where tenant_id = :t and id = :vid""")
                    .param("actor", TenantContext.current().userAccountId()).param("t", tenant)
                    .param("vid", slot.covering().id()).update();
        }
        events.publishEvent(new OrgUnitChanged(tenant, id, "CLOSED", effectiveFrom, Set.of()));
        return find(id, effectiveFrom).orElseThrow();
    }

    /**
     * Removes a planned (not yet effective) version of a unit, e.g. a planned move or close that blocks a close
     * ({@code future-versions-exist}). The version before it is extended over the freed period and re-validated
     * there; paths are re-derived. Audited as {@code REMOVE_PLANNED_VERSION}.
     */
    @Transactional
    public OrgUnitView removePlannedVersion(UUID id, UUID versionId, int expectedVersion) {
        UUID tenant = TenantContext.current().tenantId();
        LocalDate today = clock.today();
        assertCan(CorePermissions.ORG_UNIT_UPDATE, id, today);
        lockTenantTree(tenant);
        versions.checkAndBump(EffectiveDatedVersions.ORG_UNIT, tenant, id, expectedVersion,
                TenantContext.current().userAccountId(), "Org unit " + id);
        Removal removal = versions.removePlanned(EffectiveDatedVersions.ORG_UNIT, tenant, id, versionId, today);
        OrgUnitView extended = find(id, removal.extended().validFrom()).orElseThrow();
        if ("ACTIVE".equals(extended.status())) {
            validate(tenant, id, removal.removed().validFrom(), removal.removed().validTo(), new Attributes(
                    extended.nameVi(), extended.nameEn(), extended.typeId(), extended.parentId(),
                    extended.legalEntityId(), extended.locationId(), extended.costCentreId(), extended.managerUserId(),
                    extended.sortOrder()));
        }
        Set<UUID> moved = normalisePaths(tenant);
        bumpOthers(tenant, id, moved);
        audit.record(new BusinessAction("REMOVE_PLANNED_VERSION", "core", "org_unit", id, extended.legalEntityId(),
                Map.of("versionId", versionId.toString(), "validFrom", removal.removed().validFrom().toString())));
        events.publishEvent(new OrgUnitChanged(tenant, id, "VERSION_REMOVED", removal.removed().validFrom(),
                Set.copyOf(moved)));
        return find(id, removal.removed().validFrom()).orElseThrow();
    }

    // ------------------------------------------------------------------------------------------------ pickers

    /**
     * Active users to pick as a unit manager (P1 {@code manager_user_id}): id, login, display name only. For callers
     * who may create or change org units, so the org form works without {@code core.user.view}.
     */
    @Transactional(readOnly = true)
    public List<LookupItem> managerCandidates(String query, int limit) {
        assertMayWriteSomeUnit();
        String q = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        return jdbc.sql("""
                        select id, login, coalesce(display_name, login) as name from core.user_account
                         where tenant_id = :t and status = 'ACTIVE'
                           and (cast(:q as text) is null or lower(login) like :q or lower(coalesce(display_name, '')) like :q)
                         order by lower(coalesce(display_name, login)) limit :limit""")
                .param("t", TenantContext.current().tenantId()).param("q", q).param("limit", limit)
                .query((rs, n) -> new LookupItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }

    /**
     * Cost centres of {@code legalEntityId} valid on {@code asOf} (id, code, name) for the org form, without
     * {@code core.cost_centre.view}. Data scope: the caller must be able to create a root in that legal entity or to
     * create / change at least one unit of it on that date; otherwise the legal entity looks missing (404).
     */
    @Transactional(readOnly = true)
    public List<LookupItem> costCentreCandidates(UUID legalEntityId, LocalDate asOf) {
        assertMayWriteSomeUnit();
        LocalDate date = asOf == null ? clock.today() : asOf;
        UUID tenant = TenantContext.current().tenantId();
        if (!mayWriteInLegalEntity(tenant, legalEntityId, date)) {
            throw ApiException.notFound("Legal entity", legalEntityId);
        }
        return jdbc.sql("""
                        select id, code, name from core.cost_centre
                         where tenant_id = :t and legal_entity_id = :le
                           and valid_from <= :d and (valid_to is null or valid_to > :d)
                         order by code""")
                .param("t", tenant).param("le", legalEntityId).param("d", date)
                .query((rs, n) -> new LookupItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }

    private void assertMayWriteSomeUnit() {
        if (!authz.hasPermission(CorePermissions.ORG_UNIT_CREATE) && !authz.hasPermission(CorePermissions.ORG_UNIT_UPDATE)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "Picking org unit references needs core.org_unit.create or core.org_unit.update");
        }
    }

    private boolean mayWriteInLegalEntity(UUID tenant, UUID legalEntityId, LocalDate date) {
        if (authz.can(CorePermissions.ORG_UNIT_CREATE, new AuthorizationTarget.LegalEntity(legalEntityId), date)
                || authz.can(CorePermissions.ORG_UNIT_UPDATE, new AuthorizationTarget.LegalEntity(legalEntityId), date)) {
            return true;
        }
        for (String permission : List.of(CorePermissions.ORG_UNIT_CREATE, CorePermissions.ORG_UNIT_UPDATE)) {
            SqlFragment scope = authz.scopeFor(permission, date).toSql(SCOPE_COLUMNS, "s");
            if (jdbc.sql("select exists (select 1 from core.org_unit_version v where v.tenant_id = :t"
                            + " and v.legal_entity_id = :le and v.valid_during @> cast(:d as date) and " + scope.sql() + ")")
                    .param("t", tenant).param("le", legalEntityId).param("d", date).params(scope.params())
                    .query(Boolean.class).single()) {
                return true;
            }
        }
        return false;
    }

    /** Units whose versions were split or re-pathed by a write of another unit get a new lock token too. */
    private void bumpOthers(UUID tenant, UUID id, Set<UUID> moved) {
        List<UUID> others = moved.stream().filter(m -> !m.equals(id)).toList();
        if (!others.isEmpty()) {
            jdbc.sql("update core.org_unit set version = version + 1, updated_at = now(), updated_by = :actor"
                            + " where tenant_id = :t and id in (:ids)")
                    .param("actor", TenantContext.current().userAccountId()).param("t", tenant).param("ids", others)
                    .update();
        }
    }

    // ------------------------------------------------------------------------------------------------ validation

    /** Validates a version over [from, to) ({@code to} null = open-ended). */
    private void validate(UUID tenant, UUID id, LocalDate from, LocalDate to, Attributes a) {
        Map<String, Object> w = new HashMap<>();
        w.put("t", tenant);
        w.put("id", id);
        w.put("from", from);
        w.put("to", to);
        if (!exists("select 1 from core.org_unit_type where tenant_id = :t and id = :type and is_active",
                with(w, "type", a.typeId()))) {
            throw ApiException.unprocessable("unknown-org-unit-type", "Org unit type " + a.typeId()
                    + " does not exist or is inactive");
        }
        if (!exists("""
                        select 1 from core.legal_entity le join core.legal_entity_version x
                               on x.tenant_id = le.tenant_id and x.legal_entity_id = le.id
                         where le.tenant_id = :t and le.id = :le and le.status <> 'DISSOLVED'
                           and x.valid_during @> cast(:from as date)""", with(w, "le", a.legalEntityId()))) {
            throw ApiException.unprocessable("legal-entity-not-in-effect",
                    "Legal entity " + a.legalEntityId() + " does not exist, is dissolved or not in effect on " + from);
        }
        if (a.locationId() != null && !exists("""
                        select 1 from core.location where tenant_id = :t and id = :loc and legal_entity_id = :le
                           and (status = 'ACTIVE' or closed_from > :from)""", with(w, "loc", a.locationId()))) {
            throw ApiException.unprocessable("invalid-location",
                    "Location " + a.locationId() + " is not an active location of the unit's legal entity");
        }
        if (a.costCentreId() != null && !exists("""
                        select 1 from core.cost_centre where tenant_id = :t and id = :cc and legal_entity_id = :le
                           and valid_from <= :from and (valid_to is null or valid_to > :from)""",
                with(w, "cc", a.costCentreId()))) {
            throw ApiException.unprocessable("invalid-cost-centre",
                    "Cost centre " + a.costCentreId() + " is not valid for the unit's legal entity on " + from);
        }
        if (a.managerUserId() != null && !exists(
                "select 1 from core.user_account where tenant_id = :t and id = :mgr and status = 'ACTIVE'",
                with(w, "mgr", a.managerUserId()))) {
            throw ApiException.unprocessable("invalid-manager", "Manager " + a.managerUserId() + " is not an active user");
        }
        String window = "daterange(cast(:from as date), cast(:to as date), '[)')";
        if (a.parentId() == null) {
            // One root per legal entity (DDL: "NULL = root (one root per legal entity)").
            if (exists("select 1 from core.org_unit_version where tenant_id = :t and org_unit_id <> :id"
                    + " and parent_id is null and legal_entity_id = :le and status = 'ACTIVE'"
                    + " and valid_during && " + window, w)) {
                throw ApiException.conflict("legal-entity-has-root",
                        "The legal entity already has a root unit in that period; choose a parent unit");
            }
        } else {
            w.put("parent", a.parentId());
            w.put("label", label(id));
            if (a.parentId().equals(id)) {
                throw ApiException.unprocessable("org-unit-cycle", "A unit cannot be its own parent");
            }
            // The parent must be active on the start date and over the whole period, in the same legal entity.
            if (!exists("select 1 from core.org_unit_version where tenant_id = :t and org_unit_id = :parent"
                    + " and status = 'ACTIVE' and valid_during @> cast(:from as date)", w)
                    || exists("select 1 from core.org_unit_version where tenant_id = :t and org_unit_id = :parent"
                    + " and status <> 'ACTIVE' and valid_during && " + window, w)
                    || exists("select 1 from (select max(coalesce(valid_to, 'infinity')) as last_day"
                    + " from core.org_unit_version where tenant_id = :t and org_unit_id = :parent) x"
                    + " where x.last_day < coalesce(cast(:to as date), 'infinity')", w)) {
                throw ApiException.unprocessable("invalid-parent",
                        "The parent unit must be active for the whole period from " + from);
            }
            if (exists("select 1 from core.org_unit_version where tenant_id = :t and org_unit_id = :parent"
                    + " and legal_entity_id <> :le and valid_during && " + window, with(w, "le", a.legalEntityId()))) {
                throw ApiException.unprocessable("parent-in-other-legal-entity",
                        "The parent unit belongs to another legal entity in that period");
            }
            // No cycle: the new parent must not be the unit itself or one of its descendants in the period.
            if (exists("select 1 from core.org_unit_version where tenant_id = :t and org_unit_id = :parent"
                    + " and path ~ cast('*.' || :label || '.*' as lquery) and valid_during && " + window, w)) {
                throw ApiException.unprocessable("org-unit-cycle",
                        "The parent unit is a descendant of this unit; that would create a cycle");
            }
        }
        if (exists("select 1 from core.org_unit_version where tenant_id = :t and parent_id = :id"
                + " and legal_entity_id <> :le and valid_during && " + window, with(w, "le", a.legalEntityId()))) {
            throw ApiException.unprocessable("children-in-other-legal-entity",
                    "Child units belong to another legal entity in that period; move them first");
        }
    }

    private static Map<String, Object> with(Map<String, Object> params, String key, Object value) {
        params.put(key, value);
        return params;
    }

    private boolean exists(String sql, Map<String, Object> params) {
        return jdbc.sql("select exists (" + sql + ")").params(params).query(Boolean.class).single();
    }

    // ------------------------------------------------------------------------------------------------ path upkeep

    /**
     * Restores the path invariant for the whole tenant tree and returns the units whose path changed. Each pass
     * (1) re-paths versions from their parent's version on the start date (one level deeper per pass) and
     * (2) splits a version where its parent's path changes inside the version's period. Converges in about two
     * passes per tree level; a well-formed tree is left untouched.
     */
    private Set<UUID> normalisePaths(UUID tenant) {
        Set<UUID> moved = new LinkedHashSet<>();
        for (int pass = 0; pass < MAX_NORMALISE_PASSES; pass++) {
            List<UUID> repathed = jdbc.sql("""
                            update core.org_unit_version v
                               set path = coalesce(p.path || text2ltree(replace(cast(v.org_unit_id as text), '-', '')),
                                                   text2ltree(replace(cast(v.org_unit_id as text), '-', '')))
                              from core.org_unit_version v0
                              left join core.org_unit_version p
                                     on p.tenant_id = v0.tenant_id and p.org_unit_id = v0.parent_id
                                    and p.valid_during @> v0.valid_from
                             where v.tenant_id = :t and v0.tenant_id = v.tenant_id and v0.id = v.id
                               and v.path <> coalesce(p.path || text2ltree(replace(cast(v.org_unit_id as text), '-', '')),
                                                      text2ltree(replace(cast(v.org_unit_id as text), '-', '')))
                            returning v.org_unit_id""")
                    .param("t", tenant).query(UUID.class).list();
            moved.addAll(repathed);
            // Split child versions at dates where their parent's path changes inside the child's period.
            List<Map.Entry<UUID, LocalDate>> cuts = jdbc.sql("""
                            select distinct on (v.id) v.id, p.valid_from as cut
                              from core.org_unit_version v
                              join core.org_unit_version p
                                on p.tenant_id = v.tenant_id and p.org_unit_id = v.parent_id
                               and p.valid_from > v.valid_from and (v.valid_to is null or p.valid_from < v.valid_to)
                              join core.org_unit_version q
                                on q.tenant_id = p.tenant_id and q.org_unit_id = p.org_unit_id and q.valid_to = p.valid_from
                             where v.tenant_id = :t and q.path <> p.path
                             order by v.id, p.valid_from""")
                    .param("t", tenant)
                    .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class))).list();
            for (Map.Entry<UUID, LocalDate> cut : cuts) {
                LocalDate oldEnd = jdbc.sql("select valid_to from core.org_unit_version where tenant_id = :t and id = :id")
                        .param("t", tenant).param("id", cut.getKey())
                        .query((rs, n) -> Optional.ofNullable(rs.getObject(1, LocalDate.class))).single().orElse(null);
                jdbc.sql("update core.org_unit_version set valid_to = :d where tenant_id = :t and id = :id")
                        .param("d", cut.getValue()).param("t", tenant).param("id", cut.getKey()).update();
                copyVersion(tenant, cut.getKey(), cut.getValue(), oldEnd, null);
            }
            if (repathed.isEmpty() && cuts.isEmpty()) {
                return moved;
            }
        }
        throw new IllegalStateException("Org tree paths of tenant " + tenant + " did not converge");
    }

    private void insertVersion(UUID tenant, UUID id, LocalDate from, LocalDate to, String status, Attributes a) {
        Map<String, Object> p = attributeParams(tenant, a);
        p.put("vid", Ids.newId());
        p.put("id", id);
        p.put("from", from);
        p.put("to", to);
        p.put("status", status);
        p.put("label", label(id));
        // The path is provisional (own label); normalisePaths() derives it from the parent right after.
        jdbc.sql("""
                        insert into core.org_unit_version (tenant_id, id, org_unit_id, valid_from, valid_to, name_vi,
                               name_en, org_unit_type_id, parent_id, path, legal_entity_id, location_id, cost_centre_id,
                               manager_user_id, sort_order, status, created_by, updated_by)
                        values (:t, :vid, :id, :from, :to, :nameVi, :nameEn, :type, :parent, text2ltree(:label), :le,
                                :loc, :cc, :mgr, :sort, :status, :actor, :actor)""")
                .params(p).update();
    }

    /** Copies a version to start at {@code from} (status changed when {@code status} is not null). */
    private void copyVersion(UUID tenant, UUID versionId, LocalDate from, LocalDate to, String status) {
        jdbc.sql("""
                        insert into core.org_unit_version (tenant_id, id, org_unit_id, valid_from, valid_to, name_vi,
                               name_en, org_unit_type_id, parent_id, path, legal_entity_id, location_id, cost_centre_id,
                               manager_position_id, manager_user_id, sort_order, status, reorg_package_id,
                               created_by, updated_by)
                        select tenant_id, :nid, org_unit_id, :from, :to, name_vi, name_en, org_unit_type_id, parent_id,
                               path, legal_entity_id, location_id, cost_centre_id, manager_position_id, manager_user_id,
                               sort_order, coalesce(cast(:status as text), status), reorg_package_id, :actor, :actor
                          from core.org_unit_version where tenant_id = :t and id = :vid""")
                .param("nid", Ids.newId()).param("from", from).param("to", to).param("status", status)
                .param("actor", TenantContext.current().userAccountId()).param("t", tenant).param("vid", versionId)
                .update();
    }

    private static Map<String, Object> attributeParams(UUID tenant, Attributes a) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", tenant);
        p.put("nameVi", a.nameVi());
        p.put("nameEn", a.nameEn());
        p.put("type", a.typeId());
        p.put("parent", a.parentId());
        p.put("le", a.legalEntityId());
        p.put("loc", a.locationId());
        p.put("cc", a.costCentreId());
        p.put("mgr", a.managerUserId());
        p.put("sort", a.sortOrder() == null ? 0 : a.sortOrder());
        p.put("actor", TenantContext.current().userAccountId());
        return p;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static String label(UUID id) {
        return id.toString().replace("-", "");
    }

    private Optional<String> pathAt(UUID tenant, UUID id, LocalDate date) {
        return jdbc.sql("""
                        select cast(path as text) from core.org_unit_version
                         where tenant_id = :t and org_unit_id = :id and valid_during @> cast(:d as date)""")
                .param("t", tenant).param("id", id).param("d", date).query(String.class).optional();
    }

    private void assertCan(String permission, UUID id, LocalDate date) {
        // Out of scope looks the same as missing: no existence oracle across scopes.
        if (!authz.can(permission, new AuthorizationTarget.OrgUnit(id), date)) {
            throw ApiException.notFound("Org unit", id);
        }
    }

    private void lockTenantTree(UUID tenant) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('core.org_unit:' || cast(:t as text), 0))")
                .param("t", tenant).query((rs, n) -> 1).single();
    }

    private Optional<OrgUnitView> find(UUID id, LocalDate date) {
        return jdbc.sql(SELECT + " where v.tenant_id = :t and v.org_unit_id = :id and v.valid_during @> cast(:d as date)")
                .param("t", TenantContext.current().tenantId()).param("id", id).param("d", date).param("nd", date)
                .query((rs, n) -> map(rs)).optional();
    }

    /**
     * Version + anchor + display names. {@code :nd} is the date the referenced names are taken at (null = the
     * version's own start, used by the history); a reference without a version on that date falls back to its
     * latest name.
     */
    private static final String SELECT = """
            select u.code as unit_code, u.version as unit_version, t.code as type_code, t.name_vi as type_name_vi,
                   t.name_en as type_name_en, le.code as le_code, lev.le_name, loc.code as loc_code, locv.loc_name,
                   cc.code as cc_code, cc.name as cc_name, coalesce(mgr.display_name, mgr.login) as mgr_name, v.*
              from core.org_unit_version v
              join core.org_unit u on u.tenant_id = v.tenant_id and u.id = v.org_unit_id
              join core.org_unit_type t on t.tenant_id = v.tenant_id and t.id = v.org_unit_type_id
              join core.legal_entity le on le.tenant_id = v.tenant_id and le.id = v.legal_entity_id
              left join lateral (
                    select coalesce(x.short_name, x.legal_name_vi) as le_name from core.legal_entity_version x
                     where x.tenant_id = v.tenant_id and x.legal_entity_id = v.legal_entity_id
                     order by (x.valid_during @> coalesce(cast(:nd as date), v.valid_from)) desc, x.valid_from desc
                     limit 1) lev on true
              left join core.location loc on loc.tenant_id = v.tenant_id and loc.id = v.location_id
              left join lateral (
                    select x.name as loc_name from core.location_version x
                     where x.tenant_id = v.tenant_id and x.location_id = v.location_id
                     order by (x.valid_during @> coalesce(cast(:nd as date), v.valid_from)) desc, x.valid_from desc
                     limit 1) locv on true
              left join core.cost_centre cc on cc.tenant_id = v.tenant_id and cc.id = v.cost_centre_id
              left join core.user_account mgr on mgr.tenant_id = v.tenant_id and mgr.id = v.manager_user_id
            """;

    private static OrgUnitView map(ResultSet rs) throws SQLException {
        return new OrgUnitView(rs.getObject("org_unit_id", UUID.class), rs.getString("unit_code"),
                rs.getObject("id", UUID.class), rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class), rs.getString("name_vi"), rs.getString("name_en"),
                rs.getObject("org_unit_type_id", UUID.class), rs.getString("type_code"),
                rs.getString("type_name_vi"), rs.getString("type_name_en"),
                rs.getObject("parent_id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                rs.getString("le_code"), rs.getString("le_name"),
                rs.getObject("location_id", UUID.class), rs.getString("loc_code"), rs.getString("loc_name"),
                rs.getObject("cost_centre_id", UUID.class), rs.getString("cc_code"), rs.getString("cc_name"),
                rs.getObject("manager_user_id", UUID.class), rs.getString("mgr_name"), rs.getInt("sort_order"),
                rs.getString("status"), rs.getInt("depth"), rs.getInt("unit_version"));
    }
}
