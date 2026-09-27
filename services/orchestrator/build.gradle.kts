val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(lib("spring-boot-starter-webmvc"))
    implementation(lib("spring-boot-starter-jdbc"))
    implementation(lib("spring-boot-starter-flyway"))
    implementation(lib("flyway-database-postgresql"))
    implementation(lib("datasource-micrometer-spring-boot"))
    runtimeOnly(lib("postgresql"))

    testImplementation(lib("spring-boot-starter-webmvc-test"))
    testImplementation(lib("spring-boot-testcontainers"))
    testImplementation(lib("spring-boot-micrometer-tracing-test"))
    testImplementation(lib("testcontainers-junit-jupiter"))
    testImplementation(lib("testcontainers-postgresql"))
    testImplementation(lib("opentelemetry-sdk-testing"))
}
