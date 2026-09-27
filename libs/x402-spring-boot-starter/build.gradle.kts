plugins {
    id("saiman.java-library")
}

dependencies {
    // Only the autoconfigure machinery in M0. The x402 wire types and SDK dependency
    // arrive with server/client support in a later milestone.
    api(libs.spring.boot.autoconfigure)
}
