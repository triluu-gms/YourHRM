// Time & Attendance module (vn.yourhrm.attendance, schema att): holiday calendars, shifts and schedules, punch import,
// daily timesheets, overtime, leave, attendance periods and the Attendance Result handed to Payroll.
// Own pool + login role yhrm_app_att (ADR-0021).
dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:auth"))
    // Core's workflow engine API: correction (att.ATT_CORRECTION) and OT approvals
    implementation(project(":shared:workflow"))
    implementation(project(":modules:core"))
    // consumer of People's published API only (assignment events, EmployeeDirectory, SPIs T&A implements)
    implementation(project(":modules:people"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
