// Spring Boot application: bootstrap and wiring only (ADR-0001). Integration, modularity and architecture tests live here.
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:auth"))
    implementation(project(":modules:core"))

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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    // The DDL baseline (database/postgresql/ddl) is loaded into a throw-away Testcontainers PostgreSQL 17.
    systemProperty("yhrm.ddl.dir", rootProject.projectDir.resolve("../database/postgresql/ddl").canonicalPath)
    systemProperty("yhrm.seed.file", projectDir.resolve("src/main/resources/dev/seed-dev-tenant.sql").canonicalPath)
    inputs.dir(rootProject.projectDir.resolve("../database/postgresql/ddl")).withPropertyName("ddl").withPathSensitivity(PathSensitivity.RELATIVE)
}
