package vn.yourhrm.people.internal.application.employment;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.internal.infra.HrDb;

/**
 * Runs a secondary derivation (insurance participation after a hire, a salary change ...) in a savepoint of the
 * current People transaction: when it fails (e.g. the legal parameter is not defined yet) only its own writes roll
 * back, the primary change commits, and the gap shows on the concurrent-employment exception report (US-PPL-025)
 * until HR re-derives.
 */
@Component
class Savepoints {

    private static final Logger log = LoggerFactory.getLogger(Savepoints.class);

    private final TransactionTemplate nested;

    Savepoints(@Qualifier(HrDb.TX) PlatformTransactionManager hrTm) {
        this.nested = new TransactionTemplate(hrTm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** The result, or {@code fallback} when {@code work} failed (logged without payload values). */
    <T> T tryRun(String what, Supplier<T> work, T fallback) {
        try {
            return nested.execute(s -> work.get());
        } catch (RuntimeException ex) {
            log.warn("{} failed and was rolled back to its savepoint: {}", what, ex.getMessage());
            return fallback;
        }
    }
}
