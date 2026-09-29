plugins {
    id("saiman.java-library")
}

description = "Model router: tiers, data-classification policy, cost caps and metrics in front of Spring AI models (ADR-0011)."

dependencies {
    // Spring AI types (ChatClient, EmbeddingModel) are part of this library's public API.
    api(platform(libs.spring.ai.bom))
    api(libs.spring.ai.client.chat)
    implementation(libs.spring.ai.openai)
    implementation(libs.spring.boot.autoconfigure)
    // Daily USD cap lives in Valkey; the counter is only touched when a StringRedisTemplate exists.
    compileOnly(libs.spring.boot.starter.data.redis)
    compileOnly(libs.micrometer.core)
    implementation(project(":libs:shared"))

    testImplementation(libs.spring.boot.starter.data.redis)
    testImplementation(libs.micrometer.core)
}
