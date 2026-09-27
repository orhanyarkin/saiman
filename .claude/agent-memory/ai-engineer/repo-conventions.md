---
name: repo-conventions
description: Saiman-specific working agreements for the ai-engineer role — catalog ownership boundary, and a stray-file quirk seen in M0
metadata:
  type: feedback
---

Never work around a missing `gradle/libs.versions.toml` entry by hardcoding a raw Maven coordinate in an owned module's `build.gradle.kts`, even though it would compile (Gradle-native `platform(spring-boot-dependencies)` supplies the version). CLAUDE.md's convention is "every dependency version lives in the catalog", and the catalog is explicitly out of ai-engineer's owned directories (`services/ingest/**`, `services/evals/**`, `config/router/**`, prompts, `evals/datasets/**`). If a needed library isn't in the catalog, prefer redesigning the code to avoid the new dependency (this worked for `spring-boot-restclient` in M0 — the idiomatic answer turned out to be `RestTestClient`, already provided by `spring-boot-starter-webmvc-test`). If it truly can't be avoided, stop and report the missing entry to the orchestrator rather than adding it.
**Why:** shared build config changes only go through the orchestrator per the agent-orchestration protocol, so parallel task owners don't clash on `gradle/libs.versions.toml`.
**How to apply:** before adding any `testImplementation`/`implementation` line, check whether the desired class is reachable through an already-catalogued starter first (inspect the resolved jar in `~/.gradle/caches/modules-2/files-2.1/...` if unsure which module a class lives in — Boot 4.1 split many familiar classes into new artifacts, see [[boot41-modularization-gotchas]]).

M0 environment quirk: running `./gradlew spotlessApply`/`check` in this worktree generates untracked Eclipse project metadata (`.project`, `.classpath`, `.settings/`, `bin/`) in every module directory, including ones I don't own. Source unknown (no `~/.gradle/init.d` script found) — likely IDE/tooling integration in this environment, not something my build files trigger. Never `git add -A`/`git add .` here; stage only the specific owned files, otherwise these Eclipse artifacts get committed. Worth a one-line flag to the orchestrator/T0 (owns `.gitignore`) so a global ignore rule can be added.
