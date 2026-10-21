// YourHRM backend: Gradle multi-project (ADR-0001). One subproject per module / shared kernel library.
rootProject.name = "yourhrm-backend"

include(":shared:common")
include(":shared:auth")
include(":modules:core")
include(":app")

project(":shared").projectDir = file("src/shared")
project(":modules").projectDir = file("src/modules")
project(":shared:common").projectDir = file("src/shared/common")
project(":shared:auth").projectDir = file("src/shared/auth")
project(":modules:core").projectDir = file("src/modules/core")
project(":app").projectDir = file("src/app")
