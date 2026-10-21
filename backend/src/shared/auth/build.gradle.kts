// Shared auth library: JWT resource server, @RequiresPermission / @PublicEndpoint, AuthorizationService API,
// ScopePredicate, ReportingLineProvider SPI. Decisions come from Core (ADR-0005/0006).
dependencies {
    api(project(":shared:common"))
    api("org.springframework.security:spring-security-core")
    api("org.springframework.security:spring-security-config")
    api("org.springframework.security:spring-security-web")
    api("org.springframework.security:spring-security-oauth2-resource-server")
    api("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework:spring-aop")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
