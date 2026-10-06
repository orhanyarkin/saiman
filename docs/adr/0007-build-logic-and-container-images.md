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

## Update (M6): publishing to GHCR (ADR-0028)
Status: Accepted (2026-10-06). Completes the "publishing comes in M6" step above; the build-logic and Buildpacks decisions are unchanged.

- **Gradle gating.** `saiman.spring-boot-service` publishes only with `-PimagePublish=true`, and then requires an explicit `-PimageTag` other than `dev`/`latest`. Credentials come from the environment (`GITHUB_ACTOR`, `GITHUB_TOKEN`) through `docker { publishRegistry { ... } }`, never from files. Without the properties the task is exactly the local `ghcr.io/orhanyarkin/saiman-<svc>:dev` build with no push, so `make images` and Compose are untouched.
- **CI.** On a push to `main` of this repository only (never PRs, forks or Dependabot branches) the `images` job builds and pushes `sha-<12 hex>-amd64` on `ubuntu-24.04` and `sha-<12 hex>-arm64` on `ubuntu-24.04-arm`, natively, for orchestrator, seller-api, ledger, ingest and evals. `packages: write` is granted to that job and to `images-manifest` only; the workflow default stays `contents: read`. The `images-manifest` job joins the two tags into `sha-<12>` with `docker buildx imagetools create` and fails unless `imagetools inspect` lists both `linux/amd64` and `linux/arm64`. There is no `latest` tag.
- **Web** is not an image: Compose serves the Vite build with a stock `nginx` image, and the replay demo goes to Cloudflare Pages, so nothing is published for it.
- **Human steps.** After the first publish, set the five GHCR packages (`saiman-orchestrator`, `saiman-seller-api`, `saiman-ledger`, `saiman-ingest`, `saiman-evals`) to public in the package settings (Fargate pulls without credentials), and link them to the repository so `GITHUB_TOKEN` can keep pushing.
- **Not provable before the first merge to `main`.** The push path, in particular arm64 on the native runner and the manifest verification, runs only on `main`; PRs never exercise it. Locally only the Gradle configuration is checked (`--dry-run`).
