package vn.yourhrm.people.internal.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.auth.api.MaskLevel;
import vn.yourhrm.shared.auth.api.SensitiveDataAccess;

/**
 * Field masking on People's own connection (BR-PPL-152, BR-CORE-034, DESIGN §9.4): sets {@code app.mask_level} for the
 * current People transaction so the {@code hr.v_*_masked} views apply the caller's level. Core's
 * {@link SensitiveDataAccess#applyMaskLevel} cannot be used: it sets the level on Core's connection (ADR-0021).
 * Unset means fail closed (PII and pay hidden).
 */
@Component
public class HrMasking {

    private final HrDb db;
    private final SensitiveDataAccess sensitive;

    public HrMasking(HrDb db, SensitiveDataAccess sensitive) {
        this.db = db;
        this.sensitive = sensitive;
    }

    /** Applies the caller's level for {@code permission} to the current People transaction and returns it. */
    @Transactional(transactionManager = HrDb.TX, propagation = Propagation.MANDATORY)
    public MaskLevel apply(String permission) {
        MaskLevel level = sensitive.maskLevelFor(permission);
        db.jdbc().sql("select set_config('app.mask_level', :l, true)").param("l", level.name())
                .query((rs, n) -> 1).single();
        return level;
    }
}
