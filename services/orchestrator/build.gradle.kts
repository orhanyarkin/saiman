plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.datasource.micrometer.spring.boot)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.boot.micrometer.tracing.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.opentelemetry.sdk.testing)
}
