# ADR-0005: Single backend stack (Java 25 + Spring Boot 4.1 + Spring AI 2.0) and single frontend stack (React + Vite)

Status: Accepted (supersedes earlier drafts: a .NET/Java/Python split, then an all-.NET variant)

## Context
The author works in .NET professionally and has production evidence there. The roles he targets — Turkish banks, capital-markets firms and international fintech — are predominantly Java/Spring, and the lack of a substantial Spring project has come up in interviews. For a one-person, agent-built project, more than one backend stack multiplies toolchains, CI jobs and failure modes.

Spring AI 2.0 (GA June 2026, on Spring Boot 4.0/4.1) covers what the AI parts need: `ChatClient` with composable tool-calling advisors, structured-output validation, MCP server annotations (`@McpTool`) on the official MCP Java SDK 2.0, vector stores including pgvector, and providers including OpenAI, Anthropic, Google and DeepSeek. The x402 Foundation ships an official Java SDK but, as far as we know, no Spring Boot integration — to be verified in M1.

## Decision
- All backend code — starter library, services, workers, evals — in Java 25 with Spring Boot 4.1 and Spring AI 2.0, one Gradle multi-project build with a version catalog.
- The open-source contribution is `x402-spring-boot-starter`, built on the official x402 Java SDK. If M1 finds an existing, maintained Spring integration, we contribute to it instead and record that here.
- Frontend: React 19 + Vite (ADR-0002).
- Infra languages (Terraform, Helm YAML, SQL) are not application stacks.

## Consequences
+ The portfolio shows "production .NET at work + serious Spring project" — the profile of teams running mixed C#/Java estates.
+ One build, one test runner, one set of conventions for agents and CI.
− Slower start in a less familiar stack; mitigated by the "Spring notes" requirement in every task report and by reviewing every PR personally.
Revisit if: a required capability only exists outside the JVM and can't be called over HTTP.
