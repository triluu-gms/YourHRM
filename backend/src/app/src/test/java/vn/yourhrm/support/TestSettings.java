package vn.yourhrm.support;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;

/**
 * Settings of a pretend T&A module (tests only): an effective-dated INT allowed at every level and a tenant ENUM.
 */
public class TestSettings implements SettingCatalogue {

    public static final String OT_CAP = "att.test_ot_cap_hours";
    public static final String SHIFT_MODE = "att.test_shift_mode";

    @Override
    public String module() {
        return "att";
    }

    @Override
    public List<SettingDefinition> settings() {
        return List.of(
                new SettingDefinition(OT_CAP, "INT", List.of(), 40, Set.of("TENANT", "LEGAL_ENTITY", "LOCATION"), true,
                        false, "Trần giờ làm thêm (test)", "Overtime cap (test)"),
                new SettingDefinition(SHIFT_MODE, "ENUM", List.of("FIXED", "FLEX"), "FIXED", Set.of("TENANT"), false,
                        false, "Chế độ ca (test)", "Shift mode (test)"));
    }

    /** The release job's part: register the definitions as superuser. */
    public static void register(JdbcTemplate admin) {
        admin.update("""
                insert into core.setting_definition (setting_key, owner_module, value_type, json_schema, default_value,
                       allowed_levels, is_effective_dated, is_sensitive, description_vi, description_en) values
                  ('att.test_ot_cap_hours', 'att', 'INT', null, '40', ARRAY['LEGAL_ENTITY','LOCATION','TENANT'], true,
                   false, 'Trần giờ làm thêm (test)', 'Overtime cap (test)'),
                  ('att.test_shift_mode', 'att', 'ENUM', '{"enum":["FIXED","FLEX"]}', '"FIXED"', ARRAY['TENANT'], false,
                   false, 'Chế độ ca (test)', 'Shift mode (test)')
                on conflict do nothing""");
    }
}
