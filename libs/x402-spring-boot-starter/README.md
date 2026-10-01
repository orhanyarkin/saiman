# x402-spring-boot-starter

Spring Boot starter for x402 v2 payments: the `exact`
scheme with EIP-3009 test USDC on **Base Sepolia only** (ADR-0008). Server side: annotate a Spring
MVC handler with `@RequiresPayment`. Client side: a `RestClient` interceptor that answers `402`,
asks a `SpendGuard` before signing, signs and retries.

## Quickstart (server)

```java
@RestController
class ReportController {

    @GetMapping("/v1/report")
    @RequiresPayment(price = "10000", description = "Daily report") // 0.01 test USDC, atomic units
    Report report() {
        return new Report("...");
    }
}
```

```yaml
x402:
  server:
    pay-to: ${X402_SELLER_PAYTO_ADDRESS} # public payout address; startup fails without it
```

A request without a valid payment gets `402` with a `PAYMENT-REQUIRED` header. Every bean is
`@ConditionalOnMissingBean`, so a custom `FacilitatorClient` or `PaymentNonceStore` replaces the
default (x402.org facilitator, Redis nonce store when Spring Data Redis is present).

## Payment flows: `authorization` (default) and `upfront`

x402 v2 (spec section 6.1) names the flow in `accepts[].extra.paymentFlow`. Choose it per handler:

```java
@PostMapping("/v1/answers")
@RequiresPayment(price = "20000", paymentFlow = PaymentFlow.UPFRONT)
Answer answer(@RequestBody Question question, HttpServletRequest request) {
    // X402PaymentContext.settled(request) is true here; transactionHash(request) is the settlement tx
    return llm.answer(question);
}
```

| | `AUTHORIZATION` (default) | `UPFRONT` |
|---|---|---|
| Order | verify → handler → settle (only after a 2xx) | verify → settle → handler |
| Offer on the wire | unchanged, no `paymentFlow` key | `extra.paymentFlow: "upfront"` |
| Handler answers non-2xx | nothing settled, nonce claim released | buyer gets that status **plus** `PAYMENT-RESPONSE`; `X402PaidRequestFailedEvent` |
| Handler throws | container error page, nothing settled | `500 application/problem+json` plus `PAYMENT-RESPONSE`; event with `handler_exception` |
| Settle fails | `402`, handler output discarded | `402`, handler never runs |

Use `UPFRONT` for handlers that spend money before they know whether they can answer (e.g. an LLM
call): no unpaid work is ever done. The price is that a paid request can still fail; the seller has
no key to refund on chain, so listen for `X402PaidRequestFailedEvent` and record what you owe (a
credit note). In both flows the nonce claim of a settled or ambiguous payment is never released, so
one authorization never buys two handler runs, and the authorization-window checks run before any
facilitator call.

The client accepts `authorization` and `upfront` offers, rejects anything else (e.g. `escrow`) before
signing, and prefers `authorization` when both are offered. A non-2xx answer after paying is
ambiguous in either flow (`AmbiguousPaymentException`).

Check that a facilitator accepts an upfront offer (read-only `/verify`, never `/settle`):

```bash
./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer bootRun --args="testnet-check --flow=upfront"
```

## Observability

Observation `x402.server.payment` with low-cardinality keys `x402.network`, `x402.scheme`,
`x402.asset`, `x402.payment_flow` and `x402.outcome` (e.g. `settled`, `replayed`,
`settlement_failed`, `paid_not_served`). Metrics: `x402.payments{network,outcome}` with outcome
`settled` or `failed` (each payment counted once, so outcomes can be summed), `x402.payment.amount`
(settled amounts), `x402.payments.paid_not_served{network}` (upfront payments that were settled,
already counted as `settled`, and then not served) and `x402.payment.paid_not_served.amount`
(their amounts, in atomic units).

## Upgrade notes (pre-1.0)

The public API may still change before 1.0. Source-incompatible changes so far:

- `RequiresPaymentInterceptor`'s public constructor gained an `ApplicationEventPublisher`
  parameter (the upfront flow publishes settlement events from the interceptor). Only code that
  constructs the interceptor itself instead of using the auto-configured bean is affected.
