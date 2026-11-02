// People module (vn.yourhrm.people, schema hr): person, employment, assignment, salary record, contract, decision,
// dependants, bank accounts, separation, statutory labour reports. Own pool + login role yhrm_app_hr (ADR-0021).
dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:auth"))
    implementation(project(":shared:workflow"))
    implementation(project(":modules:core"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Statutory exports (labour book, 6-monthly report, dependant registration list)
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
