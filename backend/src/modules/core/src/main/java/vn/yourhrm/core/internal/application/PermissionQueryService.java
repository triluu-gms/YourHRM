package vn.yourhrm.core.internal.application;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model of the global permission catalogue (US-CORE-033/034: matrix grouped by module / function). */
@Service
public class PermissionQueryService {

    private final JdbcClient jdbc;

    public PermissionQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PermissionView(String code, String module, String functionCode, String action, String nameVi,
            String nameEn, boolean sensitive, boolean privileged, boolean requiresStepUp, boolean active) {
    }

    @Transactional(readOnly = true)
    public List<PermissionView> list(String module, boolean includeInactive) {
        return jdbc.sql("""
                        select permission_code, owner_module, function_code, action, name_vi, name_en,
                               is_sensitive, is_privileged, requires_step_up, is_active
                          from core.permission
                         where (cast(:m as text) is null or owner_module = :m)
                           and (:all or is_active)
                         order by owner_module, function_code, permission_code""")
                .param("m", module == null || module.isBlank() ? null : module)
                .param("all", includeInactive)
                .query((rs, n) -> new PermissionView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8),
                        rs.getBoolean(9), rs.getBoolean(10)))
                .list();
    }
}
