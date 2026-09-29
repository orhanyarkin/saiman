plugins {
    java
    id("org.springframework.boot") version libs.versions.spring.boot.get()
    id("com.diffplug.spotless") version libs.versions.spotless.get()
}

group = "io.github.orhanyarkin.x402.sample"
version = "0.1.0-SNAPSHOT"
description = "Standalone console client demonstrating the x402 starter: buy, replay, new-wallet, testnet-check"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Consumers bring their own Spring Boot platform; this mirrors that exactly rather than
    // relying on Boot's dependency-management Gradle plugin.
    implementation(platform(libs.spring.boot.dependencies))
    annotationProcessor(platform(libs.spring.boot.dependencies))
    testImplementation(platform(libs.spring.boot.dependencies))

    // RestClient + Boot's own auto-configuration only: no web server, no MVC. Boot infers
    // WebApplicationType.NONE automatically since no servlet/reactive web classes are present
    // (see ConsoleBuyerApplication) -- "zero config beyond properties" needs no extra wiring here.
    implementation(libs.spring.boot.starter.restclient)

    // Resolved from mavenLocal() (see settings.gradle.kts); published by
    // `make x402-publish-local` before this build runs (the `x402-sample`/`x402-*` Makefile
    // targets all depend on it).
    implementation("io.github.orhanyarkin:x402-spring-boot-starter:0.1.0-SNAPSHOT")

    // The starter deliberately does not expose org.web3j:crypto as an `api` dependency (only
    // Sign.SignatureData appears package-private, never in public method signatures; see the
    // starter's own build.gradle.kts). `new-wallet` needs raw EC key generation (ECKeyPair, Keys,
    // Numeric), which is outside the starter's public API, so this sample depends on the same
    // library directly, with the same lean-runtime exclusions.
    implementation(libs.web3j.crypto) {
        exclude(group = "io.vertx")
        exclude(group = "org.connid")
        exclude(group = "io.consensys.protocols", module = "jc-kzg-4844")
        exclude(group = "io.consensys.tuweni")
    }

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    mainClass = "io.github.orhanyarkin.x402.sample.ConsoleBuyerApplication"
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    mainClass = "io.github.orhanyarkin.x402.sample.ConsoleBuyerApplication"
    // BootRun extends JavaExec, whose `environment` (a snapshot of the process environment,
    // including X402_BUYER_PRIVATE_KEY) is part of the task's serialized configuration-cache state
    // -- caching this task risks baking one run's environment (and therefore the buyer key) into a
    // reusable cache entry that a later, differently-keyed invocation could replay.
    notCompatibleWithConfigurationCache(
            "JavaExec's environment (which includes X402_BUYER_PRIVATE_KEY) would be captured in the configuration cache")
}

tasks.withType<Test>().configureEach {
    // Mirrors the root build's testnet-tag policy (ADR-0008): CI stays hermetic, `-PincludeTestnet`
    // opts in locally. This standalone build doesn't inherit the root build's convention plugin, so
    // the same few lines are repeated here.
    val includeTestnet = providers.gradleProperty("includeTestnet").isPresent
    useJUnitPlatform {
        if (!includeTestnet) excludeTags("testnet")
    }
}

spotless {
    java {
        palantirJavaFormat(libs.versions.palantir.java.format.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
