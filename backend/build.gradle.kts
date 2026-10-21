// Root build: shared conventions for every subproject (ADR-0002 technology baseline).
plugins {
    java
    id("org.springframework.boot") version "4.0.8" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

val springModulithVersion = "2.0.8"

allprojects {
    group = "vn.yourhrm"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    if (childProjects.isNotEmpty()) return@subprojects   // :shared and :modules are folders only
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
            mavenBom("org.springframework.modulith:spring-modulith-bom:$springModulithVersion")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = 1
        maxHeapSize = "768m"
        jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off", "-Duser.timezone=UTC")
        // The user's environment sets JAVA_TOOL_OPTIONS=-Djava.vendor=...; do not leak it into test JVMs.
        environment("JAVA_TOOL_OPTIONS", "")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}
