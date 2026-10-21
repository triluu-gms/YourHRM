package vn.yourhrm.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Wraps the application DataSource with the {@link StatementRecorder} (tests only). */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingDataSourceConfig {

    @Bean
    static BeanPostProcessor statementRecorder() {
        return StatementRecorder.postProcessor();
    }
}
