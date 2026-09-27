plugins {
    id("saiman.spring-boot-service")
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)

    testImplementation(libs.spring.boot.starter.webmvc.test)
}
