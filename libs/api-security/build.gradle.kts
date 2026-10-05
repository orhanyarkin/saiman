plugins {
    id("saiman.java-library")
    `java-test-fixtures`
}

description = "API authentication: static role tokens behind Spring Security's resource-server seam (ADR-0023)."

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.webmvc)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
}
