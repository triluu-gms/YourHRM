// Core module (vn.yourhrm.core): users, roles, permissions, data scopes, authorisation service.
dependencies {
    implementation(project(":shared:common"))
    implementation(project(":shared:auth"))
    implementation(project(":shared:workflow"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    // Document service (ADR-0015): Excel import / templates, DOCX merge, HTML -> PDF/A rendering
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    implementation("com.deepoove:poi-tl:1.12.2")
    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.28")
    implementation("org.jsoup:jsoup:1.21.2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
