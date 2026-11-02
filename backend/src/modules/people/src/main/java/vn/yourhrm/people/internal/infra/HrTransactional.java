package vn.yourhrm.people.internal.infra;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.transaction.annotation.Transactional;

/**
 * A transaction on People's pool ({@link HrDb#TX}). Use it on every People service method that touches {@code hr};
 * a plain {@code @Transactional} would open a transaction on Core's pool instead. Calls to Core APIs inside it run in
 * Core's own transaction and commit independently (ADR-0021): call them before the People writes they depend on,
 * or make them idempotent.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Transactional(transactionManager = HrDb.TX)
public @interface HrTransactional {

    @AliasFor(annotation = Transactional.class)
    boolean readOnly() default false;
}
