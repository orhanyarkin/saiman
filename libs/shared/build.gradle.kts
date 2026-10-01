plugins {
    id("saiman.java-library")
}

description = "Shared contracts for Saiman services: event records, Money, OTel helpers."

dependencies {
    // Annotations only (no runtime Jackson): keep Money's wire shape identical in every service.
    compileOnly(libs.jackson.annotations)

    testImplementation(libs.jackson.databind)
}
