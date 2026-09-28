// A library other builds consume from a Maven repository (ADR-0008): today only the x402 starter.
// Consumers bring their own Spring Boot version, so the published POM carries plain resolved
// versions and never imports the Boot BOM that the build uses internally.
plugins {
    id("saiman.java-library")
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    // Javadoc is published for IDEs; missing tags must not fail the build.
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// Gradle module metadata would carry the Boot platform to consumers; the POM alone is enough.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            versionMapping {
                usage("java-api") { fromResolutionResult() }
                usage("java-runtime") { fromResolutionResult() }
            }
            pom {
                name = project.name
                description = providers.provider { project.description ?: project.name }
                url = "https://github.com/orhanyarkin/saiman"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                scm {
                    url = "https://github.com/orhanyarkin/saiman"
                }
                withXml {
                    // Drop the Boot BOM import that `platform(...)` adds; versionMapping already
                    // wrote a concrete version on every dependency.
                    val root = asNode()
                    root.children()
                        .filterIsInstance<groovy.util.Node>()
                        .filter { it.name().toString().endsWith("dependencyManagement") }
                        .forEach { root.remove(it) }
                }
            }
        }
    }
}
