---
name: springdoc-jspecify-openapi31
description: springdoc 3.1.1 on Boot 4.1 ignores JSpecify @Nullable (type-use); a ModelConverter bean fixes it; other springdoc quirks hit in the orchestrator contract
metadata:
  type: reference
---

- JSpecify `@Nullable` is TYPE_USE only, so springdoc/Jackson never see it. Fix: a `ModelConverter` bean (springdoc collects them) that calls `chain.next().resolve(...)` first, then edits the definition found via `context.getDefinedModels().get(<ref name>)` and reads `RecordComponent.getAnnotatedType().isAnnotationPresent(Nullable.class)` (works for `Outer.@Nullable Inner`). `isOpenapi31()` must return true. Unit-testable standalone with `new ModelConverters(true)` + `Json31.mapper()`.
- 3.1 nullable: `prop.addType(prop.getType()); prop.addType("null")` renders `type: ["string","null"]`; a `$ref` needs a wrapper `oneOf:[{$ref},{type:null}]`. Add null to `enum` lists too.
- Declaring any `@ApiResponse` (e.g. an error code) DROPS the inferred 200: always declare the 200 with its schema explicitly (`array = @ArraySchema(...)` for List returns).
- Spring's `ProblemDetail` reflects a bogus `properties` field; use a doc-only record with `@Schema(name="ProblemDetail")`.
- Two handlers on one path+verb (SSE + JSON `/events`): hide one with `@Operation(hidden = true)` and declare both media types on the other.
- Nested records get simple names (`Limits`, `ByTool`): rename with `@Schema(name=...)`. Jackson 2 `@JsonIgnoreProperties({"zero"})` on `Money` is honoured by springdoc (own Jackson 2 mapper).
- Postgres orders `uuid` as unsigned bytes (= hex text order); `UUID.compareTo` is signed: sort test expectations by `toString()`.
- Bash harness refuses commands with `VAR=... &&` prefixes or inline `$(...)` before javap/git-like tools: use absolute paths and separate calls.
