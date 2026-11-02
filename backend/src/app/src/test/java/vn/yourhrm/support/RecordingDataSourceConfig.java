package vn.yourhrm.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import vn.yourhrm.core.internal.application.PermissionCatalogueRegistrar;
import vn.yourhrm.core.internal.application.ProcessTypeRegistrar;
import vn.yourhrm.core.internal.application.SettingRegistrar;

/** Test-only beans: the {@link StatementRecorder} DataSource wrapper, stand-ins of the SaaS ports (plan limits, legal
 * pack, support grants, e-mail, object storage, key service, subject keys, go-live locks), pretend module catalogues and the
 * {@link RecordedEvents} event recorder. */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingDataSourceConfig {

    @Bean
    static BeanPostProcessor statementRecorder() {
        return StatementRecorder.postProcessor();
    }

    @Bean
    TestTenantLimits tenantLimits() {
        return new TestTenantLimits();
    }

    @Bean
    TestLegalParameters legalParameters() {
        return new TestLegalParameters();
    }

    @Bean
    TestSupportGrants supportGrants() {
        return new TestSupportGrants();
    }

    @Bean
    TestProcessTypes testProcessTypes() {
        return new TestProcessTypes();
    }

    @Bean
    TestEmailAdapter emailAdapter() {
        return new TestEmailAdapter();
    }

    @Bean
    TestNotificationTemplates testNotificationTemplates() {
        return new TestNotificationTemplates();
    }

    @Bean
    TestSettings testSettings() {
        return new TestSettings();
    }

    @Bean
    TestObjectStorage objectStorage() {
        return new TestObjectStorage();
    }

    @Bean
    TestDataKeys dataKeys() {
        return new TestDataKeys();
    }

    @Bean
    TestSubjectKeys subjectKeys() {
        return new TestSubjectKeys();
    }

    @Bean
    TestOpeningBalanceLocks openingBalanceLocks() {
        return new TestOpeningBalanceLocks();
    }

    @Bean
    TestHrDocuments testHrDocuments() {
        return new TestHrDocuments();
    }

    @Bean
    TestBalanceImport testBalanceImport() {
        return new TestBalanceImport();
    }

    @Bean
    TestReleaseJob testReleaseJob(PermissionCatalogueRegistrar permissions, ProcessTypeRegistrar processTypes,
            SettingRegistrar settings) {
        return new TestReleaseJob(permissions, processTypes, settings);
    }

    @Bean
    RecordedEvents recordedEvents() {
        return new RecordedEvents();
    }
}
