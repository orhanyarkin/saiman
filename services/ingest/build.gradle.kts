plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(platform(libs.spring.ai.bom))
    implementation(project(":libs:shared"))
    implementation(project(":libs:model-router"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient)
    // Redis holds the model router's daily USD cap (ADR-0011); the router refuses to start without a shared guard.
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.spring.ai.pgvector.store)
    implementation(libs.jsoup)
    implementation(libs.resilience4j.ratelimiter)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.datasource.micrometer.spring.boot)
    runtimeOnly(libs.postgresql)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.ai.bom))
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(project(":libs:test-support")) // one Postgres per test JVM (ADR-0020)
}
