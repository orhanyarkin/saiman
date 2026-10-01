plugins {
    id("saiman.java-library")
}

description = "Test-only: one Postgres, one Kafka and one Redis container per test JVM, shared by every Spring test context (ADR-0020)."

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.testcontainers)
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.kafka)
    implementation(libs.spring.boot.starter.test)
    // JdbcConnectionDetails; every module that uses Postgres brings JDBC and the driver itself.
    compileOnly(libs.spring.boot.starter.jdbc)
}
