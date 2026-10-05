---
name: springdoc-jspecify-openapi
description: springdoc 3.1.1 / swagger-core 2.2.55 on Boot 4.1 - JSpecify nullability needs a ModelConverter (not OpenApiCustomizer), required arrays get sorted, ProblemDetail schema, contract-snapshot test recipe
metadata:
  type: reference
---
Learned building the ledger's OpenAPI contract (M5 T2, 2026-10-05).

- JSpecify `@Nullable` is TYPE_USE: springdoc/swagger ignore it, and an `OpenApiCustomizer` only sees the schema tree
  (no Java types). Use a swagger-core `ModelConverter` bean (springdoc registers it in front of its own): call
  `chain.next().resolve(...)`, get the raw class via `Json.mapper().constructType(type.getType()).getRawClass()`, and
  read `recordComponent.getAnnotatedType().isAnnotationPresent(Nullable.class)`. Override `isOpenapi31()` -> true.
- In 3.1 mode use `schema.setTypes(LinkedHashSet)` (`type: ["string","null"]`); for a `$ref` property wrap as
  `new JsonSchema().oneOf(List.of(ref, new JsonSchema().types({"null"})))`.
- `Schema.setRequired` stores the list SORTED (not declaration order): assert sorted.
- Unit test with `new ModelConverters(true)` + `addConverter` + `readAll(new AnnotatedType(X.class))`; never touch
  `ModelConverters.getInstance()`.
- Defaults: GETs document only 200 with `*/*` content. Fix: `springdoc.default-produces-media-type: application/json`,
  `@ApiResponse(responseCode="200", useReturnTypeSchema=true)` plus explicit error responses with
  `@Content(mediaType="application/problem+json", schema=@Schema(implementation=ProblemDetail.class))`.
  The generated ProblemDetail schema shows a `properties` object (Jackson actually flattens it).
- Nested record simple names collide (`Item`, `Summary`): `@Schema(name=...)` on the nested record renames the component.
- `BigInteger` renders as `integer` without format.
- Snapshot test: `@SpringBootTest(RANDOM_PORT, properties="springdoc.api-docs.enabled=true")` (second cached context),
  normalise with TreeMap recursion + remove `servers` + Jackson 3 pretty printer; declare the snapshot file and the
  update env var as Gradle test inputs so a changed file or env reruns the test.

Related: [[boot4-mvc-wiring-gotchas]], [[boot4-test-standard]].
