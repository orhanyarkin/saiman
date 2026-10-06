import java.util.HashSet

plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(project(":libs:shared"))
    implementation(project(":libs:db-migrate")) // `SAIMAN_RUN_MODE=migrate` one-shot (ADR-0027)
    implementation(project(":libs:eventing")) // InboxGuard + Modulith registry defaults (ADR-0016)
    implementation(project(":libs:evm-rpc")) // Base Sepolia reads for reconciliation (ADR-0018)
    implementation(project(":libs:api-security")) // bearer-token authentication of /api/** (ADR-0023)
    implementation(platform(libs.spring.modulith.bom))
    implementation(libs.spring.modulith.starter.jdbc) // event publication registry in schema `ledger`
    implementation(libs.spring.modulith.events.kafka) // @Externalized -> ledger.entry-posted.v1
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient) // seller-api credit-note corroboration (ADR-0021)
    implementation(libs.springdoc.openapi.webmvc.api) // OpenAPI contract for the dashboard (ADR-0022); off at runtime
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.kafka.test)
    testImplementation(project(":libs:test-support")) // one Postgres and Kafka per test JVM (ADR-0020)
    testImplementation(testFixtures(project(":libs:api-security"))) // TestTokens
}

// Property tests (ADR-0019): `-Dsaiman.pbt.seed=<base seed>` and `-Dsaiman.pbt.tries=<n>` on the Gradle command
// line reach the test JVM, so a failure printed with its base seed reproduces exactly and a nightly run can scale up.
tasks.withType<Test>().configureEach {
    for (name in listOf("saiman.pbt.seed", "saiman.pbt.tries")) {
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

// OpenAPI snapshot (ADR-0022): the checked-in contract is a test input, and SAIMAN_OPENAPI_UPDATE=1 rewrites it.
tasks.named<Test>("test") {
    inputs.files(rootProject.file("docs/api/ledger.openapi.json")).withPropertyName("openApiContract")
    inputs.property("openApiUpdate", providers.environmentVariable("SAIMAN_OPENAPI_UPDATE").orElse(""))
}

// Live reconciliation against the public Base Sepolia RPC (free, no key): `./gradlew :services:ledger:testnetTest`.
// The shared conventions exclude the "testnet" tag from every other test task, so `check` stays hermetic.
tasks.register<Test>("testnetTest") {
    description = "Runs the @Tag(\"testnet\") reconciliation test against the public Base Sepolia RPC."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        setExcludeTags(HashSet<String>())
        includeTags("testnet")
    }
    outputs.upToDateWhen { false }
}
