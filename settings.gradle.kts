pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "saiman"

include(
    ":libs:shared",
    ":libs:model-router",
    ":libs:x402-spring-boot-starter",
    ":services:seller-api",
    ":services:orchestrator",
    ":services:ledger",
    ":services:ingest",
    ":services:evals",
)
