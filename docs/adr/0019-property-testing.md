# ADR-0019: Property testing with seeded generators on JUnit 5

Status: Accepted (2026-10-01).

## Context
M4's acceptance needs a balanced-postings property test. CLAUDE.md rules out jqwik: since 1.10 it ships an Anti-AI Usage Clause and it is in maintenance mode (checked 2026-10-01). junit-quickcheck is a JUnit 4 runner with no release since 2021; QuickTheories is unmaintained.

## Decision
- Properties are JUnit 5 `@ParameterizedTest` + `@MethodSource` streams of N seeds. The base seed comes from `-Dsaiman.pbt.seed` (random by default) and is printed on failure, so every failure is reproducible. Generators use `SplittableRandom`; sizes grow with the try index instead of shrinking.
- **P1:** for any event sequence (random amounts 1..10^12, duplicates, reorderings, settled-before-authorized, chain facts) every entry balances per asset and the trial balance sums to zero.
- **P2:** any permutation-with-duplicates of a payment's events yields the same balances and projection as the canonical order.
- **P3:** an entry with one posting perturbed by ±δ is rejected by the domain and by the database.
- Defaults: 1000 tries for pure properties, 50 for database-backed ones.

## Consequences
+ No extra dependency; failures reproduce from one seed.
− No automatic shrinking; generators are kept small and the failing seed plus the generated case are printed.
