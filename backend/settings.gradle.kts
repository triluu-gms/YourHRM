// YourHRM backend: Gradle multi-project (ADR-0001). One subproject per module / shared kernel library.
rootProject.name = "yourhrm-backend"

include(":shared:common")
include(":shared:auth")
include(":shared:workflow")
include(":modules:core")
include(":modules:people")
include(":modules:time-attendance")
include(":app")

project(":shared").projectDir = file("src/shared")
project(":modules").projectDir = file("src/modules")
project(":shared:common").projectDir = file("src/shared/common")
project(":shared:auth").projectDir = file("src/shared/auth")
project(":shared:workflow").projectDir = file("src/shared/workflow")
project(":modules:core").projectDir = file("src/modules/core")
project(":modules:people").projectDir = file("src/modules/people")
project(":modules:time-attendance").projectDir = file("src/modules/time-attendance")
project(":app").projectDir = file("src/app")
