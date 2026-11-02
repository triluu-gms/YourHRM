package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.yourhrm.core.api.CoreEvents.MasterDataReleased;
import vn.yourhrm.core.api.MasterData;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.AdminUnit;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.AdminUnitMapping;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.Bank;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.BankBranch;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.CodeList;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.CodeValue;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.Country;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.Currency;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.Outcome;
import vn.yourhrm.core.internal.application.MasterDataReleaseLoader.Release;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.RecordedEvents;
import vn.yourhrm.support.TestFixtures.TestUser;

/**
 * Global master data (BR-CORE-080..082): release loading (US-CORE-084), administrative units with the old→new
 * resolver (US-CORE-080/081), code lists with tenant overrides, countries, currencies, banks.
 */
class MasterDataIT extends IntegrationTest {

    /** Codes unique to this run (global tables are shared by every test). */
    static final String S = Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
    static final long RELEASE_NO = System.nanoTime() % 1_000_000_000L;
    static final LocalDate MERGER = LocalDate.of(2025, 7, 1);
    static Release release;

    @Autowired
    MasterDataReleaseLoader loader;

    @Autowired
    MasterData masterData;

    @Autowired
    JdbcClient appJdbc;

    @BeforeAll
    static void data() {
        LocalDate old = LocalDate.of(2000, 1, 1);
        release = new Release(RELEASE_NO, "Sắp xếp ĐVHC 2025 (test " + S + ")", MERGER, Instant.parse("2025-06-20T00:00:00Z"),
                List.of(new Country("Z" + S.charAt(0), "Z" + S.substring(0, 2), "Việt Nam " + S, "Viet Nam " + S,
                        "Việt Nam", "Vietnamese", true)),
                List.of(new Currency("VND", "Đồng Việt Nam", "Vietnamese dong", 0)),
                List.of(new AdminUnit("PO" + S, "PROVINCE", "Tỉnh Cũ " + S, null, null, old, MERGER, null, null),
                        new AdminUnit("DO" + S, "DISTRICT", "Huyện Cũ " + S, null, "PO" + S, old, MERGER, null, null),
                        new AdminUnit("CO1" + S, "COMMUNE", "Xã Cũ Một " + S, null, "DO" + S, old, MERGER, null, null),
                        new AdminUnit("CO2" + S, "COMMUNE", "Xã Cũ Hai " + S, null, "DO" + S, old, MERGER, null, null),
                        new AdminUnit("PN" + S, "PROVINCE", "Thành phố Mới " + S, null, null, MERGER, null, null,
                                "Nghị quyết 202/2025/QH15"),
                        new AdminUnit("CN1" + S, "COMMUNE", "Phường Hoàn Kiếm " + S, null, "PN" + S, MERGER, null, null,
                                null),
                        new AdminUnit("CN2" + S, "COMMUNE", "Phường Cửa Nam " + S, null, "PN" + S, MERGER, null, null,
                                null)),
                List.of(new AdminUnitMapping("PO" + S, "PN" + S, MERGER, "MERGE", null),
                        new AdminUnitMapping("CO1" + S, "CN1" + S, MERGER, "MERGE", null),
                        new AdminUnitMapping("CO2" + S, "CN1" + S, MERGER, "SPLIT", "phía bắc"),
                        new AdminUnitMapping("CO2" + S, "CN2" + S, MERGER, "SPLIT", "phía nam")),
                List.of(new Bank("B" + S, "970436", "BFTVVNVX", "VCB-" + S, "Ngân hàng Ngoại thương " + S, null, true),
                        new Bank("X" + S, null, null, "OLD-" + S, "Ngân hàng đã sáp nhập " + S, null, false)),
                List.of(new BankBranch("B" + S, "001", "Chi nhánh Hà Nội " + S, "PN" + S, true)),
                List.of(new CodeList("ETH_" + S, "Dân tộc", "Ethnic groups", false, true, "hr")),
                List.of(new CodeValue("ETH_" + S, "01", "Kinh", "Kinh", 1, Map.of("gso", "01"), true, null, null),
                        new CodeValue("ETH_" + S, "02", "Tày", "Tay", 2, Map.of(), true, null, null),
                        new CodeValue("ETH_" + S, "99", "Khác (ngừng)", "Other (retired)", 99, Map.of(), true, null,
                                LocalDate.of(2020, 1, 1))));
    }

    private Outcome load(Release r) {
        return loader.load(ADMIN.getDataSource(), r); // the release job's platform connection
    }

    @Test
    void releasesLoadOnceAllOrNothingAndAreAnnounced() throws Exception {
        assertThat(load(release)).isIn(Outcome.LOADED, Outcome.ALREADY_LOADED);
        assertThat(load(release)).isEqualTo(Outcome.ALREADY_LOADED);
        Release changed = new Release(RELEASE_NO, "different", release.effectiveDate(), release.publishedAt(),
                release.countries(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> load(changed)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("release-content-differs");
        // a release that fails half-way leaves nothing behind
        Release broken = new Release(RELEASE_NO + 1, "broken", MERGER, Instant.now(), List.of(), List.of(),
                List.of(new AdminUnit("BROKEN" + S, "COMMUNE", "x", null, "NO-SUCH-PARENT", MERGER, null, null, null)),
                List.of(), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> load(broken)).isNotNull();
        assertThat(ADMIN.queryForObject("select count(*) from core.master_data_release where release_no = ?",
                Integer.class, RELEASE_NO + 1)).isZero();

        assertThat(RecordedEvents.of(MasterDataReleased.class)).filteredOn(e -> e.releaseNo() == RELEASE_NO)
                .singleElement().satisfies(e -> assertThat(e.datasets()).contains("ADMIN_UNIT", "CODE_VALUE"));
        TestUser user = FIX.user(FIX.tenant("md-rel"), "u@test.local");
        mvc.perform(get("/api/v1/core/master-data/releases").with(as(user)))
                .andExpect(jsonPath("$[?(@.releaseNo == " + RELEASE_NO + ")].itemCounts.ADMIN_UNIT").value(7));
    }

    @Test
    void adminUnitsAsOfSearchAndOldToNewResolution() throws Exception {
        load(release);
        TestUser user = FIX.user(FIX.tenant("md-au"), "u@test.local");
        mvc.perform(get("/api/v1/core/master-data/admin-units?asOf=2026-01-01").with(as(user)))
                .andExpect(jsonPath("$[*].code", hasItem("PN" + S)))
                .andExpect(jsonPath("$[*].code", not(hasItem("PO" + S))));
        mvc.perform(get("/api/v1/core/master-data/admin-units?asOf=2020-01-01").with(as(user)))
                .andExpect(jsonPath("$[*].code", hasItem("PO" + S)));
        mvc.perform(get("/api/v1/core/master-data/admin-units?parent=PN" + S).with(as(user)))
                .andExpect(jsonPath("$[*].code", containsInAnyOrder("CN1" + S, "CN2" + S)));
        // accent-insensitive search (NFR-CORE-011)
        mvc.perform(get("/api/v1/core/master-data/admin-units/search?q=phuong hoan kiem " + S.toLowerCase())
                        .with(as(user)))
                .andExpect(jsonPath("$[*].code", contains("CN1" + S)));

        // merge -> one successor; split -> the user chooses; current units resolve to themselves
        mvc.perform(get("/api/v1/core/master-data/admin-units/CO1" + S + "/resolve?asOf=2026-01-01").with(as(user)))
                .andExpect(jsonPath("$.status").value("MAPPED"))
                .andExpect(jsonPath("$.candidates[*].code", contains("CN1" + S)));
        mvc.perform(get("/api/v1/core/master-data/admin-units/CO2" + S + "/resolve?asOf=2026-01-01").with(as(user)))
                .andExpect(jsonPath("$.status").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.candidates[*].code", containsInAnyOrder("CN1" + S, "CN2" + S)));
        mvc.perform(get("/api/v1/core/master-data/admin-units/CO2" + S + "/resolve?asOf=2024-01-01").with(as(user)))
                .andExpect(jsonPath("$.status").value("CURRENT"));
        assertThat(TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), null, "t", null),
                () -> masterData.resolveAdminUnit("NOPE" + S, LocalDate.of(2026, 1, 1)).status())).isEqualTo("UNKNOWN");

        // both address forms: birthplaces keep the old one
        mvc.perform(get("/api/v1/core/master-data/admin-units/CO1" + S + "/address?form=OLD").with(as(user)))
                .andExpect(jsonPath("$.text").value("Xã Cũ Một " + S + ", Huyện Cũ " + S + ", Tỉnh Cũ " + S));
        mvc.perform(get("/api/v1/core/master-data/admin-units/CO1" + S + "/address?form=NEW&asOf=2026-01-01")
                        .with(as(user)))
                .andExpect(jsonPath("$.text").value("Phường Hoàn Kiếm " + S + ", Thành phố Mới " + S));
        mvc.perform(get("/api/v1/core/master-data/admin-units/CO2" + S + "/address?form=NEW&asOf=2026-01-01")
                        .with(as(user)))
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.resolution.status").value("AMBIGUOUS"));
    }

    @Test
    void codeListsWithTenantOverridesBanksAndCountries() throws Exception {
        load(release);
        UUID tenant = FIX.tenant("md-cl");
        TestUser user = FIX.user(tenant, "u@test.local");
        String list = "ETH_" + S;
        mvc.perform(get("/api/v1/core/master-data/code-lists/" + list + "/values").with(as(user)))
                .andExpect(jsonPath("$[*].valueCode", contains("01", "02"))) // the retired value is gone
                .andExpect(jsonPath("$[0].attributes.gso").value("01"));
        mvc.perform(get("/api/v1/core/master-data/code-lists/" + list + "/values?q=tay").with(as(user)))
                .andExpect(jsonPath("$[*].labelVi", contains("Tày")));
        // a tenant row shadows the global one for that tenant only
        ADMIN.update("""
                insert into core.code_value (tenant_id, list_code, value_code, label_vi, label_en, sort_order)
                values (?, ?, '02', 'Tày (Tenant)', 'Tay (tenant)', 2)""", tenant, list);
        mvc.perform(get("/api/v1/core/master-data/code-lists/" + list + "/values").with(as(user)))
                .andExpect(jsonPath("$[1].labelVi").value("Tày (Tenant)"))
                .andExpect(jsonPath("$[1].tenantValue").value(true));
        TestUser other = FIX.user(FIX.tenant("md-cl2"), "o@test.local");
        mvc.perform(get("/api/v1/core/master-data/code-lists/" + list + "/values").with(as(other)))
                .andExpect(jsonPath("$[1].labelVi").value("Tày"));
        assertThat(TenantContext.callWith(new TenantContext(tenant, user.userId(), null, "t", null),
                () -> masterData.isValidCode(list, "99", LocalDate.of(2026, 1, 1)))).isFalse();

        mvc.perform(get("/api/v1/core/master-data/banks?q=ngan hang ngoai thuong " + S.toLowerCase()).with(as(user)))
                .andExpect(jsonPath("$[*].bankCode", contains("B" + S)));
        mvc.perform(get("/api/v1/core/master-data/banks?q=" + S).with(as(user)))
                .andExpect(jsonPath("$[*].bankCode", not(hasItem("X" + S)))); // inactive banks hidden from new entries
        mvc.perform(get("/api/v1/core/master-data/banks/B" + S + "/branches").with(as(user)))
                .andExpect(jsonPath("$[0].branchCode").value("001"));
        mvc.perform(get("/api/v1/core/master-data/countries?q=viet nam " + S.toLowerCase()).with(as(user)))
                .andExpect(jsonPath("$[0].nationalityVi").value("Việt Nam"));
        mvc.perform(get("/api/v1/core/master-data/code-lists/NO_SUCH/values").with(as(user)))
                .andExpect(status().isNotFound());
        // tenants only read the global store
        assertThatThrownBy(() -> appJdbc.sql("insert into core.bank (bank_code, short_name, name_vi) values ('Q', 'Q', 'Q')")
                .update()).rootCause().hasMessageContaining("permission denied");
    }
}
