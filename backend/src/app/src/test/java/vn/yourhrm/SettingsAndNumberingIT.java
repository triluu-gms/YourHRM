package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.Numbering;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestSettings;

/** Hierarchical settings (BR-CORE-084, US-CORE-085) and the numbering service (BR-CORE-085, US-CORE-086). */
class SettingsAndNumberingIT extends IntegrationTest {

    @Autowired
    Settings settings;

    @Autowired
    Numbering numbering;

    @Autowired
    PlatformTransactionManager txManager;

    @BeforeAll
    static void definitions() {
        TestSettings.register(ADMIN);
    }

    private static <T> T as(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", "vi-VN"), call);
    }

    private ResultActions setSetting(TestUser user, String key, String body) throws Exception {
        return mvc.perform(put("/api/v1/core/settings/" + key).with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ---------------------------------------------------------------------------------------------- settings

    @Test
    void settingsResolveTheMostSpecificTypedValue() throws Exception {
        UUID tenant = FIX.tenant("st-hier");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        mvc.perform(get("/api/v1/core/settings").with(as(owner)))
                .andExpect(jsonPath("$[*].key", hasItem("core.auto_create_user")))
                .andExpect(jsonPath("$[?(@.key == 'core.auto_create_user')].tenantValue").value(false));

        setSetting(owner, "core.auto_create_user", "{\"level\":\"TENANT\",\"value\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(true)).andExpect(jsonPath("$.source").value("TENANT"));
        setSetting(owner, "core.auto_create_user", "{\"level\":\"LEGAL_ENTITY\",\"legalEntityId\":\"" + le + "\",\"value\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("LEGAL_ENTITY"));
        assertThat(as(owner, () -> settings.booleanValue("core.auto_create_user", Settings.Scope.tenant(), null))).isTrue();
        assertThat(as(owner, () -> settings.booleanValue("core.auto_create_user", new Settings.Scope(le, null), null)))
                .isFalse();
        // a non-dated value is replaced in place (one row per level); every change is audited
        setSetting(owner, "core.auto_create_user", "{\"level\":\"TENANT\",\"value\":false}").andExpect(status().isOk());
        assertThat(ADMIN.queryForObject("select count(*) from core.setting_value where tenant_id = ? and scope_level = 'TENANT'",
                Integer.class, tenant)).isEqualTo(1);
        assertThat(ADMIN.queryForObject("select count(*) from audit.audit_event where tenant_id = ? and object_type = 'setting_value'",
                Integer.class, tenant)).isEqualTo(3);

        // validation: type, level, unknown key, enum values
        setSetting(owner, "core.auto_create_user", "{\"level\":\"TENANT\",\"value\":\"yes\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-setting-value"));
        setSetting(owner, "core.former_employee_access_days", "{\"level\":\"LEGAL_ENTITY\",\"legalEntityId\":\"" + le + "\",\"value\":30}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("level-not-allowed"));
        setSetting(owner, "core.former_employee_access_days", "{\"level\":\"TENANT\",\"value\":30.5}")
                .andExpect(status().isUnprocessableContent());
        setSetting(owner, "core.nope", "{\"level\":\"TENANT\",\"value\":1}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-setting"));
        setSetting(owner, TestSettings.SHIFT_MODE, "{\"level\":\"TENANT\",\"value\":\"ROTA\"}")
                .andExpect(status().isUnprocessableContent());
        setSetting(owner, TestSettings.SHIFT_MODE, "{\"level\":\"TENANT\",\"value\":\"FLEX\"}").andExpect(status().isOk());

        TestUser nobody = FIX.user(tenant, "nobody@test.local");
        mvc.perform(get("/api/v1/core/settings").with(as(nobody))).andExpect(status().isForbidden());
    }

    @Test
    void effectiveDatedSettingsKeepHistoryPerLevel() throws Exception {
        UUID tenant = FIX.tenant("st-dated");
        TestUser owner = FIX.owner(tenant);
        UUID le = FIX.legalEntity(tenant, "LE");
        UUID loc = FIX.location(tenant, le, "HCM");
        String key = TestSettings.OT_CAP;
        setSetting(owner, key, "{\"level\":\"TENANT\",\"value\":40,\"validFrom\":\"2026-01-01\"}").andExpect(status().isOk());
        setSetting(owner, key, "{\"level\":\"LOCATION\",\"locationId\":\"" + loc + "\",\"value\":32,\"validFrom\":\"2026-06-01\"}")
                .andExpect(status().isOk());
        setSetting(owner, key, "{\"level\":\"TENANT\",\"value\":48,\"validFrom\":\"2026-09-01\"}").andExpect(status().isOk());

        String resolve = "/api/v1/core/settings/" + key + "/resolve?locationId=" + loc + "&asOf=";
        mvc.perform(get(resolve + "2025-12-31").with(as(owner))).andExpect(jsonPath("$.source").value("DEFAULT"));
        mvc.perform(get(resolve + "2026-05-31").with(as(owner)))
                .andExpect(jsonPath("$.value").value(40)).andExpect(jsonPath("$.source").value("TENANT"));
        mvc.perform(get(resolve + "2026-06-01").with(as(owner)))
                .andExpect(jsonPath("$.value").value(32)).andExpect(jsonPath("$.source").value("LOCATION"));
        mvc.perform(get("/api/v1/core/settings/" + key + "/resolve?legalEntityId=" + le + "&asOf=2026-10-01").with(as(owner)))
                .andExpect(jsonPath("$.value").value(48));
        mvc.perform(get("/api/v1/core/settings/" + key).with(as(owner)))
                .andExpect(jsonPath("$.values[?(@.level == 'TENANT')].validFrom", contains("2026-01-01", "2026-09-01")))
                .andExpect(jsonPath("$.values[?(@.level == 'TENANT')].validTo", contains("2026-09-01", null)));
        assertThat(as(owner, () -> settings.longValue(key, new Settings.Scope(null, loc), LocalDate.of(2026, 7, 1))))
                .isEqualTo(32);
    }

    // ---------------------------------------------------------------------------------------------- numbering

    private String next(TestUser user, String code, UUID le, LocalDate date) throws Exception {
        return as(user, () -> numbering.next(code, le, date));
    }

    private ResultActions createSeries(TestUser owner, String body) throws Exception {
        return mvc.perform(post("/api/v1/core/sequences").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void seriesFormatResetPerLegalEntityAndPreview() throws Exception {
        UUID tenant = FIX.tenant("nb-fmt");
        TestUser owner = FIX.owner(tenant);
        UUID hq = FIX.legalEntity(tenant, "HQ");
        UUID mfg = FIX.legalEntity(tenant, "MFG");
        createSeries(owner, "{\"code\":\"EMPLOYEE_NO\",\"name\":\"Mã nhân viên\",\"pattern\":\"NV{SEQ:5}\"}")
                .andExpect(status().isCreated());
        createSeries(owner, """
                {"code":"CONTRACT_NO","name":"Số hợp đồng","scopeLevel":"LEGAL_ENTITY","pattern":"{SEQ:4}/{YYYY}/HĐLĐ-{LE}",
                 "resetRule":"YEARLY"}""").andExpect(status().isCreated());

        LocalDate d2026 = LocalDate.of(2026, 3, 1);
        assertThat(next(owner, "EMPLOYEE_NO", null, d2026)).isEqualTo("NV00001");
        assertThat(next(owner, "EMPLOYEE_NO", null, d2026)).isEqualTo("NV00002");
        assertThat(next(owner, "CONTRACT_NO", hq, d2026)).isEqualTo("0001/2026/HĐLĐ-HQ");
        assertThat(next(owner, "CONTRACT_NO", hq, d2026)).isEqualTo("0002/2026/HĐLĐ-HQ");
        assertThat(next(owner, "CONTRACT_NO", mfg, d2026)).isEqualTo("0001/2026/HĐLĐ-MFG");
        assertThat(next(owner, "CONTRACT_NO", hq, LocalDate.of(2027, 1, 2))).isEqualTo("0001/2027/HĐLĐ-HQ");

        mvc.perform(get("/api/v1/core/sequences/CONTRACT_NO/preview?legalEntityId=" + hq + "&onDate=2026-12-31").with(as(owner)))
                .andExpect(jsonPath("$.next").value("0003/2026/HĐLĐ-HQ"));
        assertThat(next(owner, "CONTRACT_NO", hq, d2026)).isEqualTo("0003/2026/HĐLĐ-HQ"); // the preview took nothing
        assertThatThrownBy(() -> next(owner, "CONTRACT_NO", null, d2026))
                .hasMessageContaining("needs a legal entity");

        // validation of patterns and codes
        createSeries(owner, "{\"code\":\"BAD\",\"name\":\"x\",\"pattern\":\"NV-{YYYY}\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-pattern"));
        createSeries(owner, "{\"code\":\"BAD\",\"name\":\"x\",\"pattern\":\"{SEQ:3}-{SEQ:3}\"}")
                .andExpect(status().isUnprocessableContent());
        createSeries(owner, "{\"code\":\"BAD\",\"name\":\"x\",\"pattern\":\"{DAY}-{SEQ:3}\"}")
                .andExpect(status().isUnprocessableContent());
        createSeries(owner, "{\"code\":\"EMPLOYEE_NO\",\"name\":\"x\",\"pattern\":\"{SEQ:3}\"}")
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/core/sequences/EMPLOYEE_NO").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mã NV\",\"pattern\":\"NV-{SEQ:6}\",\"resetRule\":\"NEVER\",\"version\":0}"))
                .andExpect(status().isOk());
        assertThat(next(owner, "EMPLOYEE_NO", null, d2026)).isEqualTo("NV-000003");
    }

    @Test
    void gapFreeNumbersAreUniqueUnderConcurrencyAndReturnedOnRollback() throws Exception {
        UUID tenant = FIX.tenant("nb-gap");
        TestUser owner = FIX.owner(tenant);
        createSeries(owner, "{\"code\":\"DECISION_NO\",\"name\":\"Số quyết định\",\"pattern\":\"QĐ{SEQ:4}\"}")
                .andExpect(status().isCreated());
        createSeries(owner, "{\"code\":\"TICKET_NO\",\"name\":\"Phiếu\",\"pattern\":\"T{SEQ:4}\",\"gapFree\":false}")
                .andExpect(status().isCreated());
        LocalDate d = LocalDate.of(2026, 3, 1);

        // the business transaction rolls back: a gap-free number is given back, a plain one is not
        assertThatThrownBy(() -> as(owner, () -> new TransactionTemplate(txManager).execute(s -> {
            numbering.next("DECISION_NO", null, d);
            numbering.next("TICKET_NO", null, d);
            throw new IllegalStateException("decision rejected");
        }))).hasMessage("decision rejected");
        assertThat(next(owner, "DECISION_NO", null, d)).isEqualTo("QĐ0001");
        assertThat(next(owner, "TICKET_NO", null, d)).isEqualTo("T0002");

        // 8 concurrent business transactions get 8 different consecutive numbers
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> as(owner, () -> new TransactionTemplate(txManager)
                    .execute(s -> numbering.next("DECISION_NO", null, d))));
        }
        Set<String> numbers = new TreeSet<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (Future<String> f : pool.invokeAll(calls)) {
                numbers.add(f.get());
            }
        }
        assertThat(numbers).containsExactly("QĐ0002", "QĐ0003", "QĐ0004", "QĐ0005", "QĐ0006", "QĐ0007", "QĐ0008",
                "QĐ0009");
    }
}
