plugins {
    id("saiman.published-library")
}

description = "Spring Boot starter for x402 v2 payments (exact scheme, EVM, Base Sepolia testnet only)"
version = "0.1.0-SNAPSHOT"

dependencies {
    // T0 only fixes the publishing shape; T1-T3 add web3j, RestClient, Resilience4j and Redis.
    api(libs.spring.boot.autoconfigure)
}
