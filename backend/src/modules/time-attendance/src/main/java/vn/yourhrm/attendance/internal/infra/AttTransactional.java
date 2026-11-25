package vn.yourhrm.attendance.internal.infra;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.transaction.annotation.Transactional;

/**
 * A transaction on T&amp;A's pool ({@link AttDb#TX}). Use it on every T&amp;A service method that touches {@code att};
 * a plain {@code @Transactional} would open a transaction on Core's pool instead. Calls to Core APIs inside it run in
 * Core's own transaction and commit independently (ADR-0021).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Transactional(transactionManager = AttDb.TX)
public @interface AttTransactional {

    @AliasFor(annotation = Transactional.class)
    boolean readOnly() default false;
}
