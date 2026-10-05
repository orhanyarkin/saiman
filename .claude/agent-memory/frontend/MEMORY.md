# Frontend agent memory index

- [Web stack pins and gotchas (M0)](web-stack-pins.md) — exact versions, vitest/jest-dom fix via skipLibCheck, jsdom PerformanceObserver gap
- [UI conventions established in M0](ui-conventions.md) — file layout, routing, telemetry, shadcn setup, test patterns
- [Dependency-pinning discipline (M0 review)](dependency-pinning-discipline.md) — never bypass pnpm's minimumReleaseAge policy; prefer skipLibCheck over downgrades for type-only conflicts
- [M5 ledger views conventions (T3c)](m5-ledger-views-conventions.md) — BigInt balance check, ledger poll arming, fixture ledger overlays, Lighthouse, e2e:live
- [M5 dashboard conventions (T3b)](m5-dashboard-conventions.md) — generated types, query keys, retry, e2e gracefulShutdown, test helpers
