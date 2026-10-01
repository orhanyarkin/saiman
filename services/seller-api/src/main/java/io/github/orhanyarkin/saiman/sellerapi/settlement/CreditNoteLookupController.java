package io.github.orhanyarkin.saiman.sellerapi.settlement;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/credit-notes/{paymentKey}}: lets the ledger corroborate a {@code CreditNoteIssued} it read from
 * the unauthenticated Kafka (ADR-0021, M4b audit). A forged credit note has no row here, so reconciliation reports it
 * instead of trusting it. Free (no {@code @RequiresPayment}), read-only, one row by exact key; there is no listing.
 *
 * <p><b>Guard.</b> The checks run inside the handler, so they hold whatever path form reached it ({@code
 * /internal;x/...}, percent-encoding): the {@code Host} header must be exactly one of {@code
 * seller.internal.allowed-hosts} (compose: {@code seller-api}, {@code seller-api:8081}), so a request through the
 * published port ({@code localhost:8081}) or a DNS-rebound browser page is refused with 400 before the database is
 * touched. Then the key must be a lower-case Base Sepolia test-USDC payment key, else 400. Problem details are fixed
 * text; the key is never echoed. Like ingest's {@code /internal/**} (ADR-0012), this is not authentication: any
 * container on the compose network can still ask (M6).
 */
@RestController
class CreditNoteLookupController {

    /**
     * Problem type of "no credit note for this key". The ledger treats a 404 as a definite "no row" only with this
     * type, so a 404 from anything else (a missing route, a proxy) never becomes a false finding.
     */
    static final URI NOT_FOUND_TYPE = URI.create("urn:saiman:seller-api:credit-note-not-found");

    /** {@code eip155:84532:<test USDC>:<payer>:<nonce>}, lower-case (AuthorizationRef.paymentKey()). */
    static final Pattern PAYMENT_KEY =
            Pattern.compile("eip155:84532:0x036cbd53842c5426634e7929541ec2318f3dcf7e:0x[0-9a-f]{40}:0x[0-9a-f]{64}");

    private static final URI INSTANCE = URI.create("/internal/credit-notes");

    private final JdbcClient jdbc;
    private final List<String> allowedHosts;
    private final MeterRegistry meters;

    CreditNoteLookupController(JdbcClient jdbc, InternalApiProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.allowedHosts = properties.allowedHosts();
        this.meters = meters;
    }

    @GetMapping("/internal/credit-notes/{paymentKey}")
    ResponseEntity<CreditNoteView> find(
            @PathVariable String paymentKey,
            @RequestHeader(value = HttpHeaders.HOST, required = false) @Nullable String host) {
        if (host == null || !allowedHosts.contains(host.trim().toLowerCase(Locale.ROOT))) {
            throw refuse("host_refused", HttpStatus.BAD_REQUEST, "Host not allowed", null);
        }
        if (!PAYMENT_KEY.matcher(paymentKey).matches()) {
            throw refuse("bad_key", HttpStatus.BAD_REQUEST, "Malformed payment key", null);
        }
        CreditNoteView view = jdbc.sql("""
                        SELECT payment_key, tx_hash, amount_atomic, http_status, reason_code, created_at
                          FROM credit_note WHERE payment_key = :key
                        """)
                .param("key", paymentKey)
                .query((rs, row) -> new CreditNoteView(
                        rs.getString("payment_key"),
                        rs.getString("tx_hash"),
                        rs.getLong("amount_atomic"),
                        rs.getInt("http_status"),
                        rs.getString("reason_code"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional()
                .orElseThrow(() -> refuse("not_found", HttpStatus.NOT_FOUND, "No credit note", NOT_FOUND_TYPE));
        count("found");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    private ErrorResponseException refuse(String outcome, HttpStatus status, String detail, @Nullable URI type) {
        count(outcome);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(INSTANCE); // fixed: Spring would otherwise echo the request path (the key)
        if (type != null) {
            problem.setType(type);
        }
        return new ErrorResponseException(status, problem, null);
    }

    private void count(String outcome) {
        meters.counter("saiman.seller.credit_note.lookups", "outcome", outcome).increment();
    }
}
