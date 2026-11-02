// Shared workflow client library (ADR-0007): process-type catalogue SPI, WorkflowService API and lifecycle events.
// The engine itself is implemented in Core; modules depend only on this library.
dependencies {
    api(project(":shared:common"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
