import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()
fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

group = "io.github.orhanyarkin"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Spring Boot's BOM as a Gradle platform: versions for everything Boot manages.
    implementation(platform(lib("spring-boot-dependencies")))
    annotationProcessor(platform(lib("spring-boot-dependencies")))
    testImplementation(platform(lib("spring-boot-dependencies")))

    implementation(lib("jspecify"))
    errorprone(lib("errorprone-core"))
    errorprone(lib("nullaway"))

    testImplementation(lib("spring-boot-starter-test"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.errorprone {
        disableWarningsInGeneratedCode = true
        check("NullAway", if (name == "compileTestJava") CheckSeverity.OFF else CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "io.github.orhanyarkin")
        option("NullAway:JSpecifyMode", "true")
    }
}

tasks.withType<Test>().configureEach {
    // Tests tagged "testnet" talk to Base Sepolia or x402.org; CI stays hermetic (ADR-0008).
    // Run them locally with `-PincludeTestnet`.
    val includeTestnet = providers.gradleProperty("includeTestnet").isPresent
    useJUnitPlatform {
        if (!includeTestnet) excludeTags("testnet")
    }
}

spotless {
    java {
        palantirJavaFormat(version("palantir-java-format"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
