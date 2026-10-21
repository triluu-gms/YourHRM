package vn.yourhrm.shared.common.time;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    BusinessClock businessClock() {
        return BusinessClock.system();
    }
}
