---
name: jackson3-palantir-quirks
description: Jackson 3 serialises Money.isZero() as "zero" unless mixed in; palantir spotless turns \uXXXX string escapes into literal chars (breaks Error Prone on bidi)
metadata:
  type: project
---
- Jackson 3 (Boot 4.1) serialises extra `isX()` methods on records: `Money` came out with a `zero` field. Fix in the orchestrator: `events/MoneyJsonMixin` (`@JacksonMixin(Money.class)` + `@JsonIgnoreProperties("zero")`), and `RunEventCodec` adds it via `json.rebuild().addMixIn(...)`. Other services serialising `Money` need the same.
- Jackson 3 API names: `JsonNode.isString()/asString()`, `properties()`, `json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)`; `JacksonException` is unchecked.
- `spotlessApply` (palantir-java-format) rewrites printable `\uXXXX` escapes in string literals to the literal characters; for bidi/format chars Error Prone's UnicodeDirectionalityCharacters then fails compilation. In tests build hostile strings at runtime from code points (`new String(int[] cps, 0, n)`).

**Why:** both cost a test cycle during M3 T4a (2026-10-01).
**How to apply:** any new wire DTO containing `Money`, and any test with control/bidi text.
