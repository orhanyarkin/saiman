// A standalone Gradle build: this sample resolves the x402 starter the way any external consumer
// would, from a Maven repository, not from the root saiman build (docs/design/m1-x402.md "Console
// buyer and make targets"). Run it with the root wrapper:
// `./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer <task>`.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // Only the x402 starter itself comes from mavenLocal() (published by
        // `make x402-publish-local` / `./gradlew :libs:x402-spring-boot-starter:publishToMavenLocal`);
        // everything else -- Spring Boot, web3j, Jackson, test libraries -- comes from mavenCentral(),
        // exactly as it would for a real consumer of the published starter.
        mavenLocal {
            content { includeGroup("io.github.orhanyarkin") }
        }
        mavenCentral()
    }
    versionCatalogs {
        // Reuses the root saiman build's version catalog so this sample tracks the same dependency
        // versions (Spring Boot, palantir-java-format...) without duplicating them. This is the one
        // coupling to the root build's layout; if gradle/libs.versions.toml ever moves, update this
        // path.
        create("libs") {
            from(files("../../../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "x402-console-buyer"
