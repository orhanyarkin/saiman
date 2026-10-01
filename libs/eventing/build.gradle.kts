plugins {
    id("saiman.java-library")
}

description = "Event plumbing shared by producers and consumers: Spring Modulith outbox defaults and the inbox guard (ADR-0016)."

dependencies {
    api(platform(libs.spring.modulith.bom))
    api(project(":libs:shared"))
    api(libs.spring.modulith.events.api)
    implementation(libs.spring.boot.starter.jdbc)
    compileOnly(libs.spring.boot.autoconfigure)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":libs:test-support")) // one Postgres per test JVM (ADR-0020)
    testRuntimeOnly(libs.postgresql)
}
