plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(project(":libs:shared"))
    implementation(project(":libs:x402-spring-boot-starter")) // SpendGuard SPI + the paying RestClient interceptor
    implementation(platform(libs.spring.ai.bom)) // Spring AI's ChatClient is part of the model router's API
    implementation(project(":libs:model-router")) // all LLM calls go through the router (CLAUDE.md rule 6)
    implementation(libs.spring.boot.starter.data.redis) // the router's shared daily cap and per-run scopes (Redis)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient) // RestClient.Builder for the paying seller client
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.datasource.micrometer.spring.boot)
    implementation(libs.resilience4j.circuitbreaker) // breaker on the seller client, wired programmatically
    implementation(libs.resilience4j.retry) // jittered retry on the free ticker catalogue call only (never on a paid call)
    implementation(project(":libs:eventing")) // Modulith registry defaults (ADR-0016)
    implementation(project(":libs:evm-rpc")) // safe-block chain reads for HELD resolution (ADR-0018)
    implementation(platform(libs.spring.modulith.bom))
    implementation(libs.spring.modulith.starter.jdbc) // transactional outbox: JDBC event publication registry
    implementation(libs.spring.modulith.events.kafka) // externalizes payments.*.v1 and agent.run-step.v1
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.springdoc.openapi.webmvc.api) // the dashboard contract (ADR-0022); off at runtime, on in OpenApiContractTests
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.boot.micrometer.tracing.test)
    testImplementation(project(":libs:test-support")) // one Postgres and Kafka per test JVM (ADR-0020)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(testFixtures(project(":libs:x402-spring-boot-starter"))) // TestWallets
    testImplementation(platform(libs.spring.ai.bom))
    testImplementation(testFixtures(project(":libs:model-router"))) // FakeChatModel etc. (no key, no network)
}
