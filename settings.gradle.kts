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
    ":libs:eventing",
    ":libs:evm-rpc",
    ":libs:api-security",
    ":libs:db-migrate",
    ":libs:test-support",
    ":services:seller-api",
    ":services:orchestrator",
    ":services:ledger",
    ":services:ingest",
    ":services:evals",
)
