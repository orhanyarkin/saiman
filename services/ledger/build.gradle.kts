plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(project(":libs:shared"))
    implementation(project(":libs:eventing")) // InboxGuard + Modulith registry defaults (ADR-0016)
    implementation(platform(libs.spring.modulith.bom))
    implementation(libs.spring.modulith.starter.jdbc) // event publication registry in schema `ledger`
    implementation(libs.spring.modulith.events.kafka) // @Externalized -> ledger.entry-posted.v1
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.kafka.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)
}
