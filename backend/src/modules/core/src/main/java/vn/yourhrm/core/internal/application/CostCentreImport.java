package vn.yourhrm.core.internal.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.api.ImportType;
import vn.yourhrm.core.internal.application.CostCentreService.CostCentreView;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Import of cost centres (US-CORE-025, BR-CORE-025/110/114): one row per legal entity + code; an existing cost centre
 * is updated (name, GL segment). Runs through {@link CostCentreService}, so the importing user's scope applies.
 */
@Component
public class CostCentreImport implements ImportType {

    static final String CODE = "CORE_COST_CENTRE";
    static final String ENTITY = "core.cost_centre";

    private final CostCentreService costCentres;
    private final JdbcClient jdbc;
    private final BusinessClock clock;

    public CostCentreImport(CostCentreService costCentres, JdbcClient jdbc, BusinessClock clock) {
        this.costCentres = costCentres;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Definition definition() {
        return new Definition(CODE, "core", "Trung tâm chi phí", "Cost centres", "1", CorePermissions.IMPORT_RUN,
                List.of(Column.lookup("legal_entity", "Pháp nhân", "Legal entity", "LEGAL_ENTITY", true),
                        Column.of("code", "Mã", "Code", ColumnType.TEXT, true),
                        Column.of("name", "Tên", "Name", ColumnType.TEXT, true),
                        Column.of("gl_segment", "Phân đoạn tài khoản", "GL segment", ColumnType.TEXT, false),
                        Column.of("valid_from", "Hiệu lực từ", "Valid from", ColumnType.DATE, false)),
                List.of("legal_entity", "code"), false, true, List.of(), List.of());
    }

    @Override
    public List<RowMessage> validate(ImportContext ctx, ImportRow row) {
        List<RowMessage> out = new ArrayList<>();
        String code = row.text("code");
        if (code.length() > 30 || !code.matches("[A-Za-z0-9._-]+")) {
            out.add(RowMessage.error("code", "invalid-code", "Mã chỉ gồm chữ, số, '.', '_', '-' (tối đa 30 ký tự)",
                    "The code may hold letters, digits, '.', '_', '-' (at most 30)"));
        }
        if (existing(row.id("legal_entity"), code).isPresent()) {
            out.add(RowMessage.warning("code", "will-update", "Mã đã tồn tại: dòng này cập nhật trung tâm chi phí",
                    "The code exists: this row updates the cost centre"));
        }
        return out;
    }

    @Override
    public CommitResult commit(ImportContext ctx, ImportRow row) {
        UUID le = row.id("legal_entity");
        Optional<UUID> id = existing(le, row.text("code"));
        if (id.isPresent()) {
            CostCentreView c = costCentres.get(id.get());
            costCentres.update(c.id(), row.text("name"), row.text("gl_segment"), c.ownerUserId(), c.validTo(),
                    c.version());
            return CommitResult.updated(ENTITY, c.id());
        }
        LocalDate from = row.date("valid_from") != null ? row.date("valid_from")
                : ctx.asOfDate() != null ? ctx.asOfDate() : clock.today();
        CostCentreView c = costCentres.create(le, row.text("code"), row.text("name"), row.text("gl_segment"), null,
                from, null);
        return CommitResult.created(ENTITY, c.id());
    }

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        List<String> out = new ArrayList<>();
        if (rows.stream().anyMatch(r -> "UPDATED".equals(r.action()))) {
            out.add("The job updated existing cost centres; their previous values are not restored automatically");
        }
        UUID[] created = created(rows);
        out.addAll(jdbc.sql("""
                select distinct 'Cost centre ' || c.code || ' is used by org unit ' || u.code
                  from core.org_unit_version v
                  join core.cost_centre c on c.tenant_id = v.tenant_id and c.id = v.cost_centre_id
                  join core.org_unit u on u.tenant_id = v.tenant_id and u.id = v.org_unit_id
                 where v.tenant_id = :t and v.cost_centre_id = any(:ids)""")
                .param("t", ctx.tenantId()).param("ids", created).query(String.class).list());
        return out;
    }

    @Override
    public void undo(ImportContext ctx, List<CommittedRow> rows) {
        jdbc.sql("delete from core.cost_centre where tenant_id = :t and id = any(:ids)")
                .param("t", ctx.tenantId()).param("ids", created(rows)).update();
    }

    private static UUID[] created(List<CommittedRow> rows) {
        return rows.stream().filter(r -> "CREATED".equals(r.action())).map(CommittedRow::targetId)
                .toArray(UUID[]::new);
    }

    private Optional<UUID> existing(UUID legalEntityId, String code) {
        return jdbc.sql("""
                select id from core.cost_centre where tenant_id = sys.current_tenant_id() and legal_entity_id = :le
                   and code = :c""")
                .param("le", legalEntityId).param("c", code).query(UUID.class).optional();
    }
}
