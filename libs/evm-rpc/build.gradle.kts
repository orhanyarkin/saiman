plugins {
    id("saiman.java-library")
}

description = "Read-only Base Sepolia JSON-RPC client for USDC authorization facts (ADR-0018); testnet only."

dependencies {
    api(project(":libs:shared"))
    implementation(libs.spring.boot.starter.restclient)
    implementation(libs.jackson.databind)
    // Keccak-256 for computed selectors and event topics only (ADR-0008: crypto module, no web3j core).
    implementation(libs.web3j.crypto)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.ratelimiter)
    compileOnly(libs.spring.boot.autoconfigure)

    testImplementation(libs.spring.boot.starter.test)
}

// The shared conventions exclude the "testnet" tag from every test task. This task runs only those tests:
// they call the public Base Sepolia RPC (free, no key). Run it with `./gradlew :libs:evm-rpc:testnetTest`.
tasks.register<Test>("testnetTest") {
    description = "Runs the @Tag(\"testnet\") known-answer tests against the public Base Sepolia RPC."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        setExcludeTags(emptySet()) // the shared conventions exclude it
        includeTags("testnet")
    }
    outputs.upToDateWhen { false }
}
