# Event schemas

One JSON Schema file per topic, named `<topic>.schema.json`, e.g. `payments.settled.v1.schema.json`.

Common envelope (every event):

```json
{
  "eventId": "uuid",
  "type": "payments.settled.v1",
  "occurredAt": "RFC3339",
  "producer": "seller-api",
  "correlationId": "run id or request id",
  "data": {}
}
```

Rules: amounts are integer strings in atomic units plus `asset` and `decimals`; breaking changes create a new `.vN` topic; consumers dedupe on `eventId`.
