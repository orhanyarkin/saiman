plugins {
    id("saiman.published-library")
}

description = "Spring Boot starter for x402 v2 payments (exact scheme, EVM, Base Sepolia testnet only)"
version = "0.1.0-SNAPSHOT"

dependencies {
    // T0 only fixes the publishing shape; T2/T3 add RestClient, Resilience4j and Redis.
    api(libs.spring.boot.autoconfigure)

    // EIP-712/EIP-3009 signing and recovery (ADR-0008). Only Hash/Sign/Keys/ECKeyPair/
    // StructuredDataEncoder are used here -- pure in-memory crypto, no I/O -- so the heavier
    // optional pieces web3j:crypto otherwise drags in at runtime are excluded: Vert.x (built
    // for Netty 4.1, carries its own CVEs, and this starter does no networking), the tuweni
    // ecosystem and its KZG blob-commitment dependency (only used by web3j's block/transaction
    // types, which this starter never touches), and org.connid (an identity-connector
    // framework tuweni depends on transitively, likewise unused). `Sign.SignatureData` and
    // other web3j types do not appear in this starter's own public API (see
    // Eip3009TypedData's package-private signature helpers), so this is `implementation`.
    implementation(libs.web3j.crypto) {
        exclude(group = "io.vertx")
        exclude(group = "org.connid")
        exclude(group = "io.consensys.protocols", module = "jc-kzg-4844")
        exclude(group = "io.consensys.tuweni")
    }

    // Declared directly, not left to web3j's transitive resolution, so the published POM
    // carries the exact versions this starter is built and tested against: Jackson 3 matching
    // Spring Boot 4.1's own version (X402Codec also uses it), and a BouncyCastle release newer
    // than web3j:crypto's own transitive 1.80 (CVE fixes in >= 1.85).
    implementation(libs.jackson.databind)
    implementation(libs.bcprov)
}

// Guards the exclusions above: fails the build if any of web3j:crypto's heavier optional
// dependencies reappear on the runtime classpath (e.g. because a future dependency bump adds
// them back through a different path). Resolves `runtimeClasspath` at task-execution time
// (never during project configuration), so it doesn't slow down every other task -- but the
// resolution APIs available here aren't configuration-cache serialisable, hence the explicit
// opt-out below rather than fighting Gradle's closure-capture rules for a one-off, always-run
// verification task.
tasks.register("checkExcludedTransitiveDependencies") {
    group = "verification"
    description =
        "Fails if web3j:crypto's excluded transitive dependencies (Vert.x, org.connid, tuweni, jc-kzg-4844) reappear on the runtime classpath."
    notCompatibleWithConfigurationCache("resolves the runtimeClasspath configuration to inspect dependency groups")
    doLast {
        val forbiddenGroups = setOf("io.vertx", "org.connid", "io.consensys.protocols", "io.consensys.tuweni")
        val offenders =
            configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
                .map { it.moduleVersion.id }
                .filter { it.group in forbiddenGroups }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "web3j:crypto's excluded transitive dependencies reappeared on the runtime classpath: " +
                    offenders.joinToString { "${it.group}:${it.name}:${it.version}" })
        }
    }
}

tasks.named("check") { dependsOn("checkExcludedTransitiveDependencies") }
