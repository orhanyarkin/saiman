---
name: spring-dao-exception-classification
description: Spring 7 DAO hierarchy traps when classifying retryable vs poison Kafka records; spring-kafka 4.1 classifier API.
metadata:
  type: reference
---

- `DataAccessResourceFailureException` (and `CannotGetJdbcConnectionException`) extends `NonTransientDataAccessResourceException` -> a "NonTransient = not retryable" rule dead-letters valid facts on a DB outage. SQLSTATE class 08 (e.g. 08006) translates to it. Verified with javap on spring-tx 7.0.9.
- `CannotCreateTransactionException` is a `TransactionException`, not a `DataAccessException`.
- `RecoverableDataAccessException` extends `DataAccessException` directly (neither Transient nor NonTransient).
- spring-kafka 4.1 replaced spring-retry's BinaryExceptionClassifier with `ExceptionMatcher`; rather than rely on its matching semantics, `FailedRecordProcessor.setBackOffFunction((record, ex) -> BackOff)` lets you decide per exception (FixedBackOff(0,0) = quarantine on first delivery). Ledger uses this via `RecordFailure`.
- Hikari marks a connection broken on 08xxx, and the rollback exception then overrides the app exception ("Application exception overridden by rollback exception").
- Ledger test gotcha: `TestPayment.authorized()` etc. mint a fresh random eventId per call; capture the event once before string-replacing its id.

Related: [[boot4-mvc-wiring-gotchas]]
