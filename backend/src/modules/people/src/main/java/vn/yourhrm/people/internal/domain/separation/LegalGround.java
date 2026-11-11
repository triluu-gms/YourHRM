package vn.yourhrm.people.internal.domain.separation;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The legal grounds of a separation (căn cứ chấm dứt, BR-PPL-070, US-PPL-060): the seeded list of the backlog, fixed in
 * code because each maps to a {@code hr.separation.separation_type} of the DDL CHECK. Tenants add sub-reasons under a
 * ground (tenant setting {@code hr.separation.sub_reasons}), never grounds. The statutory attributes of a ground (SI/UI
 * code, which party's notice rules apply, severance kind) are legal parameters ({@code separation.legal_grounds}),
 * all {@code [verify-legal]}.
 *
 * <ul>
 *   <li>{@code employerInitiated}: the grounds the protected-employee rule blocks (BR-PPL-073, DDL
 *       {@code separation_protected_ck}: EMPLOYER_UNILATERAL, DISMISSAL, REDUNDANCY);</li>
 *   <li>{@code decisionType}: the {@code DecisionService.TYPES} code of the termination decision.</li>
 * </ul>
 */
public enum LegalGround {

    CONTRACT_EXPIRY("CONTRACT_EXPIRY", "AUTOMATIC", "BLLĐ 2019 Điều 34.1", "Hết hạn hợp đồng lao động",
            "Contract expiry"),
    MUTUAL_AGREEMENT("MUTUAL_AGREEMENT", "MUTUAL", "BLLĐ 2019 Điều 34.3", "Hai bên thỏa thuận chấm dứt hợp đồng",
            "Termination by mutual agreement"),
    EMPLOYEE_RESIGNATION("RESIGNATION", "EMPLOYEE", "BLLĐ 2019 Điều 35",
            "Người lao động đơn phương chấm dứt hợp đồng (xin nghỉ việc)", "Employee resignation"),
    EMPLOYER_UNILATERAL("EMPLOYER_UNILATERAL", "EMPLOYER", "BLLĐ 2019 Điều 36",
            "Người sử dụng lao động đơn phương chấm dứt hợp đồng", "Unilateral termination by the employer"),
    REDUNDANCY("REDUNDANCY", "EMPLOYER", "BLLĐ 2019 Điều 42–43",
            "Cho thôi việc do thay đổi cơ cấu, công nghệ, lý do kinh tế, chia tách, sáp nhập",
            "Redundancy (restructuring, economic reasons, merger)"),
    DISMISSAL("DISMISSAL", "EMPLOYER", "BLLĐ 2019 Điều 125", "Sa thải (xử lý kỷ luật)", "Dismissal (disciplinary)"),
    PROBATION_FAILED("PROBATION_FAILED", "EMPLOYER", "BLLĐ 2019 Điều 27", "Không đạt yêu cầu thử việc",
            "Probation not passed"),
    RETIREMENT("RETIREMENT", "AUTOMATIC", "BLLĐ 2019 Điều 36.1.đ, 169", "Nghỉ hưu", "Retirement"),
    DEATH("DEATH", "AUTOMATIC", "BLLĐ 2019 Điều 34.5", "Người lao động chết", "Death of the employee"),
    MISSING_DECLARED("MISSING_DECLARED", "AUTOMATIC", "BLLĐ 2019 Điều 34.5",
            "Tòa án tuyên bố người lao động mất tích hoặc đã chết", "Declared missing or dead by a court"),
    ENTITY_CLOSURE("ENTITY_CLOSURE", "EMPLOYER", "BLLĐ 2019 Điều 34.7",
            "Người sử dụng lao động chấm dứt hoạt động", "Cessation of the employer's operations");

    /** Grounds whose termination is employer-initiated in the sense of BR-PPL-073 (DDL separation_protected_ck). */
    public static final Set<String> PROTECTED_TYPES = Set.of("EMPLOYER_UNILATERAL", "DISMISSAL", "REDUNDANCY");

    private final String separationType;
    private final String initiatedBy;
    private final String article;
    private final String nameVi;
    private final String nameEn;

    LegalGround(String separationType, String initiatedBy, String article, String nameVi, String nameEn) {
        this.separationType = separationType;
        this.initiatedBy = initiatedBy;
        this.article = article;
        this.nameVi = nameVi;
        this.nameEn = nameEn;
    }

    public String code() {
        return name();
    }

    public String separationType() {
        return separationType;
    }

    public String initiatedBy() {
        return initiatedBy;
    }

    public String article() {
        return article;
    }

    public String nameVi() {
        return nameVi;
    }

    public String nameEn() {
        return nameEn;
    }

    public boolean employerInitiated() {
        return PROTECTED_TYPES.contains(separationType);
    }

    public String decisionType() {
        return this == RETIREMENT ? "RETIREMENT" : "TERMINATION";
    }

    public static Optional<LegalGround> of(String code) {
        return Arrays.stream(values()).filter(g -> g.name().equals(code)).findFirst();
    }
}
