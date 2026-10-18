# Real `/actuator/health` bodies (Spring Boot 4.1.1)

Recorded from the orchestrator under `@SpringBootTest` (a throwaway test, not kept) on 2026-10-18; `<name>.json` is the body,
`<name>.status` the HTTP status. Used by `test-readiness.sh`, because the fixture that `readiness.sh` was first written against
(`{"status":"UP",...}`) had the keys in an order Boot never produces: **Jackson writes the keys alphabetically, so `status` is the
last key** at the top level and inside a component that has details.

| Fixture | Settings |
| --- | --- |
| `up-components-never` | defaults (`show-components: never`, what the services ship with) |
| `up-components-always` | `management.endpoint.health.show-components=always` (what the ECS demo sets) |
| `down-components-never` | a failing `HealthIndicator`, defaults |
| `down-components-always` | the same, components shown: component `probe` is DOWN |
| `down-details-always` | the same with `show-details=always` (never used by the demo): details come before `status`, and carry a path, a database name and the text `visible-detail` that must never reach a log |

The only edit: the local checkout path inside `diskSpace.details.path` was replaced by `/workspace/service/.`.
