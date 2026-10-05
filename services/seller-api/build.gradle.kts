plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(platform(libs.spring.ai.bom)) // Spring AI's ChatClient is part of the model router's API
    implementation(project(":libs:x402-spring-boot-starter"))
    implementation(project(":libs:shared"))
    implementation(project(":libs:api-security")) // service tokens on /internal/** only (ADR-0023)
    implementation(project(":libs:model-router")) // all LLM calls go through the router (CLAUDE.md rule 6)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient) // x402 server's default facilitator client needs a RestClient.Builder
    implementation(libs.spring.boot.starter.data.redis) // x402 server's Redis-backed payment nonce store (Redis)
    implementation(libs.spring.boot.starter.validation) // @Pattern on the ticker path variable
    implementation(libs.spring.boot.starter.jdbc) // settlement records (schema seller_api)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(project(":libs:eventing")) // Modulith registry defaults (ADR-0016)
    implementation(platform(libs.spring.modulith.bom))
    implementation(libs.spring.modulith.starter.jdbc) // transactional outbox: JDBC event publication registry
    implementation(libs.spring.modulith.events.kafka) // externalizes payments.settled.v1 / payments.failed.v1
    implementation(libs.spring.boot.starter.kafka)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.ai.bom))
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(testFixtures(project(":libs:model-router")))
    testImplementation(testFixtures(project(":libs:x402-spring-boot-starter")))
    testImplementation(project(":libs:test-support")) // one Postgres, Kafka and Redis per test JVM (ADR-0020)
    testImplementation(testFixtures(project(":libs:api-security"))) // TestTokens: known tokens and digests
}
