package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** Spring Modulith boundary verification (ADR-0001 decision 2): fails the build on violations. */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(YourHrmApplication.class);

    @Test
    void modulesAreDetected() {
        assertThat(MODULES.stream().map(ApplicationModule::getBasePackage).map(Object::toString))
                .anySatisfy(p -> assertThat(p).contains("vn.yourhrm.core"))
                .anySatisfy(p -> assertThat(p).contains("vn.yourhrm.shared.common"))
                .anySatisfy(p -> assertThat(p).contains("vn.yourhrm.shared.auth"));
    }

    @Test
    void verifiesModularStructure() {
        MODULES.verify();
    }
}
