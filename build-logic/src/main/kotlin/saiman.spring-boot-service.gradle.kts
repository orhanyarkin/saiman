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
// Images live in a namespace we own; a bare `saiman/<name>` would resolve to someone else's Docker Hub account.
//
// Publishing is opt-in (ADR-0007 update, ADR-0028): `-PimagePublish=true -PimageTag=sha-<12 hex>-<arch>` pushes
// that tag to GHCR with GITHUB_ACTOR / GITHUB_TOKEN from the environment (never from a file). Without
// `imagePublish` the build is the local `:dev` image and nothing leaves the machine.
tasks.named<BootBuildImage>("bootBuildImage") {
    val wantPublish = providers.gradleProperty("imagePublish").map { it.toBoolean() }.getOrElse(false)
    val tag = providers.gradleProperty("imageTag").getOrElse("dev")
    imageName = "ghcr.io/orhanyarkin/saiman-${project.name}:$tag"
    providers.gradleProperty("imagePlatform").orNull?.let { imagePlatform = it }
    if (wantPublish) {
        require(tag != "dev" && tag != "latest") {
            "-PimagePublish=true needs an immutable -PimageTag (e.g. sha-<12 hex>-amd64), not 'dev' or 'latest'"
        }
        publish = true
        docker {
            publishRegistry {
                url = "https://ghcr.io"
                username = providers.environmentVariable("GITHUB_ACTOR")
                password = providers.environmentVariable("GITHUB_TOKEN")
            }
        }
    }
}
