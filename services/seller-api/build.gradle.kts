plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(project(":libs:x402-spring-boot-starter"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient) // x402 server's default facilitator client needs a RestClient.Builder
    implementation(libs.spring.boot.starter.data.redis) // x402 server's Redis-backed payment nonce store (Valkey)
    implementation(libs.spring.boot.starter.validation) // @Pattern on the ticker path variable

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(testFixtures(project(":libs:x402-spring-boot-starter")))
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
