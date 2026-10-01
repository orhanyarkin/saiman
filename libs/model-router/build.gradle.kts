plugins {
    id("saiman.java-library")
    `java-test-fixtures`
}

description = "Model router: tiers, data-classification policy, cost caps and metrics in front of Spring AI models (ADR-0011)."

dependencies {
    // Spring AI types (ChatClient, EmbeddingModel) are part of this library's public API.
    api(platform(libs.spring.ai.bom))
    api(libs.spring.ai.client.chat)
    implementation(libs.spring.ai.openai)
    implementation(libs.spring.boot.autoconfigure)
    // Circuit breaker per route for the OpenAI primary -> fallback model switch; core module only.
    implementation(libs.resilience4j.circuitbreaker)
    // Daily USD cap lives in Redis; the counter is only touched when a StringRedisTemplate exists.
    compileOnly(libs.spring.boot.starter.data.redis)
    compileOnly(libs.micrometer.core)
    // Money (USD micro-dollars) appears in the public API of CostGuard.
    api(project(":libs:shared"))
    annotationProcessor(libs.spring.boot.configuration.processor)

    // FakeEmbeddingModel, FakeChatModel, FakeModelRouter for ingest / seller-api tests (no key, no network).
    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testFixturesApi(platform(libs.spring.ai.bom))
    testFixturesApi(libs.spring.ai.client.chat)
    testFixturesImplementation(libs.jspecify)

    testImplementation(libs.spring.boot.starter.data.redis)
    testImplementation(libs.micrometer.core)
    testImplementation(project(":libs:test-support")) // one Redis per test JVM (ADR-0020)
}
