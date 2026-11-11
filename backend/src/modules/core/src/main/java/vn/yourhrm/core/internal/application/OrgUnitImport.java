package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.core.internal.application.OrgUnitService.Attributes;
import vn.yourhrm.core.internal.application.OrgUnitService.OrgUnitView;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Import of the org tree at go-live (US-CORE-015, BR-CORE-010/110/114): one row per unit code; the parent is named by
 * its code and must exist already or be listed above in the same file (dependency order). An existing unit gets a new
 * version from the row's date. Runs through {@link OrgUnitService}, so the importing user's scope applies.
 */
@Component
public class OrgUnitImport implements ImportType {

    static final String CODE = "CORE_ORG_UNIT";
    static final String ENTITY = "core.org_unit";

    private final OrgUnitService orgUnits;
    private final JdbcClient jdbc;
    private final BusinessClock clock;

    public OrgUnitImport(OrgUnitService orgUnits, JdbcClient jdbc, BusinessClock clock) {
        this.orgUnits = orgUnits;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Definition definition() {
        return new Definition(CODE, "core", "Đơn vị tổ chức", "Org units", "1", CorePermissions.IMPORT_RUN,
                List.of(Column.of("code", "Mã đơn vị", "Unit code", ColumnType.TEXT, true),
                        Column.of("name_vi", "Tên tiếng Việt", "Name (Vietnamese)", ColumnType.TEXT, true),
                        Column.of("name_en", "Tên tiếng Anh", "Name (English)", ColumnType.TEXT, false),
                        Column.lookup("unit_type", "Loại đơn vị", "Unit type", "ORG_UNIT_TYPE", true),
                        new Column("parent_code", "Mã đơn vị cha", "Parent unit code", ColumnType.TEXT, false, null,
                                false, "Để trống cho đơn vị gốc; đơn vị cha phải có sẵn hoặc nằm ở dòng phía trên",
                                "Empty for a root; the parent must exist or be listed in a row above"),
                        Column.lookup("legal_entity", "Pháp nhân", "Legal entity", "LEGAL_ENTITY", true),
                        Column.lookup("location", "Địa điểm", "Location", "LOCATION", false),
                        Column.of("valid_from", "Hiệu lực từ", "Valid from", ColumnType.DATE, false)),
                List.of("code"), false, true, List.of(), List.of());
    }

    @Override
    public List<RowMessage> validate(ImportContext ctx, ImportRow row) {
        List<RowMessage> out = new ArrayList<>();
        String code = row.text("code");
        String parent = row.text("parent_code");
        if (parent != null) {
            if (parent.equalsIgnoreCase(code)) {
                out.add(RowMessage.error("parent_code", "parent-is-self", "Đơn vị không thể là cha của chính nó",
                        "A unit cannot be its own parent"));
            } else if (!ctx.seenKeys().contains(parent.toUpperCase(Locale.ROOT)) && unitId(parent).isEmpty()) {
                out.add(RowMessage.error("parent_code", "parent-not-found", "Không tìm thấy đơn vị cha " + parent
                        + " (phải có sẵn hoặc nằm ở dòng phía trên)", "Parent unit " + parent
                        + " not found (it must exist or be listed above)"));
            }
        }
        if (unitId(code).isPresent()) {
            out.add(RowMessage.warning("code", "will-update", "Mã đã tồn tại: dòng này tạo phiên bản mới của đơn vị",
                    "The code exists: this row adds a version of the unit"));
        }
        return out;
    }

    @Override
    public CommitResult commit(ImportContext ctx, ImportRow row) {
        LocalDate from = row.date("valid_from") != null ? row.date("valid_from")
                : ctx.asOfDate() != null ? ctx.asOfDate() : clock.today();
        UUID parentId = row.text("parent_code") == null ? null : unitId(row.text("parent_code")).orElseThrow();
        Optional<UUID> id = unitId(row.text("code"));
        if (id.isPresent()) {
            OrgUnitView current = orgUnits.get(id.get(), from);
            orgUnits.changeFrom(id.get(), from, new Attributes(row.text("name_vi"), row.text("name_en"),
                    row.id("unit_type"), parentId, row.id("legal_entity"), row.id("location"), current.costCentreId(),
                    current.managerUserId(), current.sortOrder()), null); // an import overwrites: no lock token
            return CommitResult.updated(ENTITY, id.get());
        }
        OrgUnitView created = orgUnits.create(row.text("code"), from, new Attributes(row.text("name_vi"),
                row.text("name_en"), row.id("unit_type"), parentId, row.id("legal_entity"), row.id("location"), null,
                null, null));
        return CommitResult.created(ENTITY, created.id());
    }

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        List<String> out = new ArrayList<>();
        if (rows.stream().anyMatch(r -> "UPDATED".equals(r.action()))) {
            out.add("The job changed existing units; their previous versions are not restored automatically");
        }
        UUID[] created = created(rows);
        out.addAll(jdbc.sql("""
                select distinct 'Unit ' || c.code || ' was added under imported unit ' || p.code
                  from core.org_unit_version v
                  join core.org_unit c on c.tenant_id = v.tenant_id and c.id = v.org_unit_id
                  join core.org_unit p on p.tenant_id = v.tenant_id and p.id = v.parent_id
                 where v.tenant_id = :t and v.parent_id = any(:ids) and not v.org_unit_id = any(:ids)""")
                .param("t", ctx.tenantId()).param("ids", created).query(String.class).list());
        out.addAll(jdbc.sql("""
                select distinct 'Role assignment ' || a.id || ' is scoped to an imported unit'
                  from core.role_assignment a
                 where a.tenant_id = :t and a.scope_type = 'ORG_UNIT' and a.scope_ids && :ids""")
                .param("t", ctx.tenantId()).param("ids", created).query(String.class).list());
        return out;
    }

    @Override
    public void undo(ImportContext ctx, List<CommittedRow> rows) {
        UUID[] created = created(rows);
        jdbc.sql("delete from core.org_unit_version where tenant_id = :t and org_unit_id = any(:ids)")
                .param("t", ctx.tenantId()).param("ids", created).update();
        jdbc.sql("delete from core.org_unit where tenant_id = :t and id = any(:ids)")
                .param("t", ctx.tenantId()).param("ids", created).update();
    }

    private static UUID[] created(List<CommittedRow> rows) {
        return rows.stream().filter(r -> "CREATED".equals(r.action())).map(CommittedRow::targetId)
                .toArray(UUID[]::new);
    }

    private Optional<UUID> unitId(String code) {
        return jdbc.sql("select id from core.org_unit where tenant_id = sys.current_tenant_id() and upper(code) = upper(:c)")
                .param("c", code).query(UUID.class).optional();
    }
}
