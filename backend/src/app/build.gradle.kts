// Spring Boot application: bootstrap and wiring only (ADR-0001). Integration, modularity and architecture tests live here.
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:auth"))
    implementation(project(":shared:workflow"))
    implementation(project(":modules:core"))
    implementation(project(":modules:people"))
    implementation(project(":modules:time-attendance"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testImplementation("org.apache.poi:poi-ooxml:5.4.1")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    // The DDL baseline (database/postgresql/ddl) is loaded into a throw-away Testcontainers PostgreSQL 17.
    systemProperty("yhrm.ddl.dir", rootProject.projectDir.resolve("../database/postgresql/ddl").canonicalPath)
    systemProperty("yhrm.seed.file", projectDir.resolve("src/main/resources/dev/seed-dev-tenant.sql").canonicalPath)
    inputs.dir(rootProject.projectDir.resolve("../database/postgresql/ddl")).withPropertyName("ddl").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.named<Test>("test") {
    // Performance tests (tests/performance/README.md) never run in the default build.
    useJUnitPlatform { excludeTags("perf") }
}

// T&A performance tests (NFR-ATT-002 / 003): ./gradlew :app:perfTest -Dperf.employees=10000 (needs Docker).
// Every -Dperf.* property of the Gradle invocation is passed to the test JVM.
tasks.register<Test>("perfTest") {
    description = "Runs the performance tests (JUnit tag 'perf') against Testcontainers PostgreSQL 17."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    shouldRunAfter(tasks.named("test"))
    maxHeapSize = providers.systemProperty("perf.heap").getOrElse("3g")
    outputs.upToDateWhen { false }
    System.getProperties().stringPropertyNames().filter { it.startsWith("perf.") && it != "perf.heap" }
        .forEach { systemProperty(it, System.getProperty(it)) }
    systemProperty("perf.reportFile", System.getProperty("perf.reportFile")
        ?: layout.buildDirectory.file("perf/att-perf.csv").get().asFile.absolutePath)
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
