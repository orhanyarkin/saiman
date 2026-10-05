plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(project(":libs:shared")) // retrieval contract (RetrieveRequest/Response)

    implementation(libs.spring.boot.starter.restclient) // RestClient to ingest; also brings Jackson
    implementation(libs.resilience4j.retry)
    annotationProcessor(libs.spring.boot.configuration.processor)
}
