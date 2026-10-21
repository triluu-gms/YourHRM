package vn.yourhrm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.shared.auth.api.PublicEndpoint;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * ArchUnit guardrails (US-CORE-039, ADR-0001/0003/0006). The endpoint rule is also checked against a
 * deliberately broken fixture ({@code archfixture.UnguardedController}) to prove that it bites.
 */
class ArchitectureTest {

    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("vn.yourhrm");

    /** Every @RestController handler declares @RequiresPermission or @PublicEndpoint. */
    static final ArchRule EVERY_ENDPOINT_DECLARES_A_PERMISSION = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().areMetaAnnotatedWith(RequestMapping.class)
            .should(declareAuthorisation())
            .because("US-CORE-039: no endpoint may bypass the authorisation service");

    @Test
    void everyEndpointDeclaresAPermissionOrIsMarkedPublic() {
        EVERY_ENDPOINT_DECLARES_A_PERMISSION.check(PRODUCTION);
    }

    @Test
    void theEndpointRuleDetectsAnUnguardedHandler() {
        JavaClasses fixture = new ClassFileImporter().importPackages("archfixture");
        EvaluationResult result = EVERY_ENDPOINT_DECLARES_A_PERMISSION.evaluate(fixture);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).singleElement()
                .satisfies(d -> assertThat(d).contains("UnguardedController.open()"));
    }

    @Test
    void tenantIsNeverReadFromRequestHeaders() {
        // Request headers may be read only by the TenantContextFilter (for the X-Request-Id correlation id).
        noClasses().that().resideInAPackage("vn.yourhrm..")
                .and().doNotHaveSimpleName("TenantContextFilter")
                .should().callMethod(jakarta.servlet.http.HttpServletRequest.class, "getHeader", String.class)
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.bind.annotation.RequestHeader")
                .because("ADR-0003: tenant and user come only from the validated JWT, never from headers")
                .check(PRODUCTION);
    }

    @Test
    void modulesDoNotUseOtherModulesInternals() {
        noClasses().that().resideInAPackage("vn.yourhrm.shared..")
                .should().dependOnClassesThat().resideInAPackage("vn.yourhrm.core..")
                .because("ADR-0001: dependencies point from modules to the shared kernel, never back")
                .check(PRODUCTION);
    }

    @Test
    void domainHasNoWebOrJdbcDependencies() {
        noClasses().that().resideInAPackage("vn.yourhrm.core.internal.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..",
                        "org.springframework.jdbc..", "jakarta.servlet..")
                .check(PRODUCTION);
    }

    @Test
    void jdbcAccessStaysInInfrastructure() {
        noClasses().that().resideInAPackage("vn.yourhrm.core.internal.web..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .because("ADR-0003: data access runs in transactional application services / repositories")
                .check(PRODUCTION);
    }

    private static ArchCondition<JavaMethod> declareAuthorisation() {
        return new ArchCondition<>("be annotated with @RequiresPermission or @PublicEndpoint") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean ok = method.isAnnotatedWith(RequiresPermission.class)
                        || method.isAnnotatedWith(PublicEndpoint.class)
                        || method.getOwner().isAnnotatedWith(RequiresPermission.class);
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " declares neither @RequiresPermission nor @PublicEndpoint"));
                }
            }
        };
    }

}
