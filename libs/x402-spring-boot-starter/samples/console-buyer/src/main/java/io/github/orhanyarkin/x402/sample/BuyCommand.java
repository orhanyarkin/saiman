package io.github.orhanyarkin.x402.sample;

import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The {@code buy} command: pays for a resource once through {@link X402PaymentInterceptor} and
 * prints the result.
 */
@Component
final class BuyCommand {

    private static final Path LAST_PAYMENT_FILE = Path.of("build", "last-payment.txt");
    private static final Pattern TX_HASH_PATTERN = Pattern.compile("0x[0-9a-fA-F]{64}");

    private final RestClient.Builder restClientBuilder;
    private final ObjectProvider<X402PaymentInterceptor> interceptorProvider;
    private final X402Codec codec;

    BuyCommand(
            RestClient.Builder restClientBuilder,
            ObjectProvider<X402PaymentInterceptor> interceptorProvider,
            X402Codec codec) {
        this.restClientBuilder = restClientBuilder;
        this.interceptorProvider = interceptorProvider;
        this.codec = codec;
    }

    /** @return {@code true} if the request completed (2xx); {@code false} on a handled failure */
    boolean run(String url, PrintStream out, PrintStream err) throws Exception {
        X402PaymentInterceptor interceptor = interceptorProvider.getIfAvailable();
        if (interceptor == null) {
            err.println("no buyer key configured: set X402_BUYER_PRIVATE_KEY, or run `new-wallet` first");
            return false;
        }

        AtomicReference<String> sentPaymentSignature = new AtomicReference<>();
        RestClient restClient = restClientBuilder
                .clone()
                .requestInterceptor(interceptor)
                .requestInterceptor(new PaymentSignatureCapturingInterceptor(sentPaymentSignature))
                .build();

        String idempotencyKey = "console-buyer-" + UUID.randomUUID();
        ResponseEntity<String> response = restClient
                .get()
                .uri(url)
                .header(X402PaymentInterceptor.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .retrieve()
                .toEntity(String.class);

        out.println("status: " + response.getStatusCode().value());
        out.println("body: " + SafePrint.of(response.getBody()));

        String settlementHeader = response.getHeaders().getFirst(X402Headers.PAYMENT_RESPONSE);
        if (settlementHeader != null) {
            printSettlement(codec.decodeSettlementResponse(settlementHeader), out);
        }

        String sent = sentPaymentSignature.get();
        if (sent != null) {
            SecretFiles.writeOwnerOnly(LAST_PAYMENT_FILE, sent);
        }
        return true;
    }

    /**
     * Package-private and static so it can be unit-tested directly with a hand-built {@link
     * SettlementResponse}, independent of an actual HTTP round trip: {@link
     * X402PaymentInterceptor} already refuses to treat a settlement with a missing or malformed
     * {@code transaction} as a success (so {@code run} above would never actually reach this
     * method with one in practice), but this method's own defense is worth keeping —
     * and testing — independently of that upstream guarantee.
     */
    static void printSettlement(SettlementResponse settlement, PrintStream out) {
        String transaction = settlement.transaction();
        out.println("tx hash: " + SafePrint.of(transaction));
        // Only a well-formed hash, never printed verbatim otherwise: settlement.transaction() is
        // server-supplied and not @Nullable in the record, but nothing stops a server from
        // omitting the JSON field (decodes to null) or sending garbage -- printing either straight
        // into a link would put unsanitized, potentially control-character-laden content (e.g. a
        // terminal escape sequence) on this terminal.
        if (transaction != null && TX_HASH_PATTERN.matcher(transaction).matches()) {
            out.println("https://sepolia.basescan.org/tx/" + transaction);
        }
    }
}
