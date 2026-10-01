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

    testImplementation(libs.spring.boot.starter.test)
}
