// Shared kernel: TenantContext, ids, errors (ProblemDetail), time, tenant-aware transactions.
dependencies {
    api("org.springframework.modulith:spring-modulith-api")
    api("org.springframework:spring-web")
    api("org.springframework:spring-webmvc")
    api("org.springframework:spring-orm")
    api("org.springframework:spring-jdbc")
    api("jakarta.persistence:jakarta.persistence-api")
    api("jakarta.servlet:jakarta.servlet-api")
    api("jakarta.validation:jakarta.validation-api")
    implementation("org.hibernate.orm:hibernate-core")
    implementation("org.slf4j:slf4j-api")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
