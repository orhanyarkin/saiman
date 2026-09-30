plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(platform(libs.spring.ai.bom)) // Spring AI's ChatClient is part of the model router's API
    implementation(project(":libs:x402-spring-boot-starter"))
    implementation(project(":libs:shared"))
    implementation(project(":libs:model-router")) // all LLM calls go through the router (CLAUDE.md rule 6)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient) // x402 server's default facilitator client needs a RestClient.Builder
    implementation(libs.spring.boot.starter.data.redis) // x402 server's Redis-backed payment nonce store (Valkey)
    implementation(libs.spring.boot.starter.validation) // @Pattern on the ticker path variable

    testImplementation(platform(libs.spring.ai.bom))
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(testFixtures(project(":libs:model-router")))
    testImplementation(testFixtures(project(":libs:x402-spring-boot-starter")))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
