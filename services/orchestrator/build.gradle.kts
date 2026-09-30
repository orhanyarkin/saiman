plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(project(":libs:shared"))
    implementation(project(":libs:x402-spring-boot-starter")) // SpendGuard SPI + the paying RestClient interceptor
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient) // RestClient.Builder for the paying seller client
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.datasource.micrometer.spring.boot)
    implementation(libs.resilience4j.circuitbreaker) // breaker on the seller client, wired programmatically
    implementation(libs.resilience4j.retry) // jittered retry on the free ticker catalogue call only (never on a paid call)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.boot.micrometer.tracing.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(testFixtures(project(":libs:x402-spring-boot-starter"))) // TestWallets
}
