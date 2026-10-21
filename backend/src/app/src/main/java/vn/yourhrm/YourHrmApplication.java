package vn.yourhrm;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;

/**
 * YourHRM modular monolith (ADR-0001). Bootstrap and wiring only: business code lives in the module
 * subprojects ({@code vn.yourhrm.core}, ...) and the shared kernel ({@code vn.yourhrm.shared.*}).
 * Application modules are the packages annotated with {@code @ApplicationModule}
 * ({@code spring.modulith.detection-strategy=explicitly-annotated}).
 */
@Modulith(systemName = "YourHRM")
@SpringBootApplication
public class YourHrmApplication {

    public static void main(String[] args) {
        // Instants are UTC end to end (DESIGN.md §4.7). It also stops pgJDBC from sending a host zone alias
        // such as "Asia/Saigon" (Windows default) that PostgreSQL rejects at connection start-up.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(YourHrmApplication.class, args);
    }
}
