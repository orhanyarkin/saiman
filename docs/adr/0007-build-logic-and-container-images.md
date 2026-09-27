# ADR-0007: Gradle build conventions in `build-logic`, container images with `bootBuildImage`, arm64 built on native runners

Status: Accepted (2026-09-27)

## Context
The Gradle multi-project build has seven modules (two libraries, five Spring Boot apps) that share the same toolchain, formatting, static analysis and dependency management. Every service must ship images for linux/amd64 (local) and linux/arm64 (ECS Fargate ARM, ADR-0004). The build must stay explainable in an interview, cache well, and run on free GitHub-hosted runners.

## Decision
- **Conventions** live in an included build, `build-logic/`, as precompiled Kotlin script plugins: `saiman.java-conventions` (toolchain 25, Spotless with palantir-java-format, Error Prone + NullAway in JSpecify mode), `saiman.java-library` and `saiman.spring-boot-service`. Modules apply one plugin id; versions come from `gradle/libs.versions.toml`, which `build-logic` reads too.
- **Dependency management** uses Gradle's native `platform(spring-boot-dependencies)`, not the `io.spring.dependency-management` plugin.
- **Container images** come from Spring Boot's `bootBuildImage` (Cloud Native Buildpacks, Paketo multi-arch builder). No Dockerfiles. One invocation builds one architecture; `-PimagePlatform=linux/arm64` selects another.
- **CI** builds amd64 images on `ubuntu-24.04` and arm64 images on `ubuntu-24.04-arm` natively, so no QEMU emulation. M0 builds images only; publishing to a registry and assembling the multi-arch manifest come in M6.

## Alternatives
- `buildSrc`: works, but any change to it invalidates the whole build, and Gradle's own guidance now prefers an included `build-logic` build.
- Root `subprojects {}` / `allprojects {}`: cross-project configuration that is hard to reason about and applies to empty parent projects too.
- `io.spring.dependency-management`: familiar from Maven-style BOM import, but duplicates what Gradle platforms do natively and interacts poorly with Gradle's own resolution rules.
- Dockerfile + `docker buildx` with QEMU: full control and a single multi-arch command, but a Dockerfile per service to maintain, and emulated arm64 builds are several times slower.

## Consequences
+ Each module's build file is a few lines; conventions are testable and cacheable, and a change to one convention only reconfigures what uses it.
+ Buildpacks give reproducible, non-root, SBOM-carrying images with no Dockerfile maintenance, and the JVM version follows the Gradle toolchain.
+ arm64 builds are native and fast on free public-repo runners.
− Paketo "tiny" images have no shell, so Docker Compose cannot health-check the apps from inside; the host polls `/actuator/health` instead.
− The first local image build downloads the Paketo builder (~1 GB).
− The multi-arch manifest is an extra CI step, deferred to M6.
Revisit if: build times exceed ~10 minutes in CI, or Fargate needs something the buildpack images can't provide.
