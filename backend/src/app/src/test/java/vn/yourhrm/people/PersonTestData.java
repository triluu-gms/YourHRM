package vn.yourhrm.people;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reference data of the person-stream tests, written as the superuser: tenant-level Core code values (so test
 * classes never interfere through global rows) and global administrative units with codes unique to these tests.
 */
final class PersonTestData {

    private PersonTestData() {
    }

    static final Map<String, List<String>> CODES = Map.of(
            "GENDER", List.of("M", "F"),
            "ETHNICITY", List.of("KINH", "TAY"),
            "RELIGION", List.of("NONE", "BUDDHISM"),
            "HI_FACILITY", List.of("01-001", "79-002"),
            "QUALIFICATION_LEVEL", List.of("UNIVERSITY", "COLLEGE", "MASTER"),
            "VOCATIONAL_SKILL_LEVEL", List.of("L1", "L2"),
            "GENERAL_EDUCATION", List.of("12/12"),
            "VISA_TYPE", List.of("LD1", "DN1"));

    /** Code lists + this tenant's values of every list the person stream validates. */
    static void codes(JdbcTemplate admin, UUID tenant) {
        CODES.forEach((list, values) -> {
            admin.update("""
                    insert into core.code_list (list_code, name_vi, name_en, is_tenant_extensible, owner_module)
                    values (?, ?, ?, true, 'hr') on conflict do nothing""", list, list, list);
            for (String v : values) {
                admin.update("""
                        insert into core.code_value (tenant_id, list_code, value_code, label_vi, label_en)
                        values (?, ?, ?, ?, ?) on conflict do nothing""", tenant, list, v, v, v);
            }
        });
    }

    /**
     * Two-level admin units valid since 2025-07-01 (province PT79, communes PT79-001 / PT79-002) and a legacy
     * three-level chain ended on that date (province PTL79 > district PTL79-D1 > commune PTL79-D1-C1).
     */
    static void adminUnits(JdbcTemplate admin) {
        admin.update("""
                insert into core.admin_unit (admin_unit_code, level, name_vi, parent_code, valid_from, valid_to) values
                  ('PT79', 'PROVINCE', 'TP Hồ Chí Minh (test)', null, '2025-07-01', null),
                  ('PT79-001', 'COMMUNE', 'Phường Sài Gòn (test)', 'PT79', '2025-07-01', null),
                  ('PT79-002', 'COMMUNE', 'Phường Bến Thành (test)', 'PT79', '2025-07-01', null),
                  ('PT01', 'PROVINCE', 'Hà Nội (test)', null, '2025-07-01', null),
                  ('PT01-001', 'COMMUNE', 'Phường Hoàn Kiếm (test)', 'PT01', '2025-07-01', null),
                  ('PTL79', 'PROVINCE', 'TP HCM cũ (test)', null, '1976-07-02', '2025-07-01'),
                  ('PTL79-D1', 'DISTRICT', 'Quận 1 cũ (test)', 'PTL79', '1976-07-02', '2025-07-01'),
                  ('PTL79-D1-C1', 'COMMUNE', 'Phường Bến Nghé cũ (test)', 'PTL79-D1', '1976-07-02', '2025-07-01')
                on conflict do nothing""");
    }
}
