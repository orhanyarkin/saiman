import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    id("saiman.java-conventions")
    id("org.springframework.boot")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

springBoot {
    buildInfo()
}

dependencies {
    implementation(lib("spring-boot-starter-actuator"))
    implementation(lib("spring-boot-starter-opentelemetry"))
}

// One architecture per invocation (ADR-0007): `./gradlew bootBuildImage -PimagePlatform=linux/arm64`.
tasks.named<BootBuildImage>("bootBuildImage") {
    imageName = "saiman/${project.name}:dev"
    providers.gradleProperty("imagePlatform").orNull?.let { imagePlatform = it }
}
