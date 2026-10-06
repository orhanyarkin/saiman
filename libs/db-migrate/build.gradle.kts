plugins {
    id("saiman.java-library")
}

description = "Per-service Flyway one-shot that runs migrations as the schema owner and exits (ADR-0027)."

dependencies {
    api(platform(libs.spring.boot.dependencies))
    // Spring Boot core + FlywayAutoConfiguration; deliberately no web, Kafka, Redis, Modulith or security.
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.database.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":libs:test-support")) // one Postgres per test JVM (ADR-0020)
    testRuntimeOnly(libs.postgresql)
}
