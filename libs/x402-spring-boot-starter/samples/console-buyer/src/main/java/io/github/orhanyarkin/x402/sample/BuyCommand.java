package io.github.orhanyarkin.x402.sample;

import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
        return run(url, "GET", null, out, err);
    }

    /**
     * Pays for {@code url} with the given method.
     *
     * @param method {@code GET} or {@code POST}
     * @param json a JSON request body sent with {@code Content-Type: application/json} (POST only;
     *     never echoed); the payment interceptor re-sends it verbatim on the paid retry
     * @return {@code true} if the request completed (2xx); {@code false} on a handled failure
     */
    boolean run(String url, String method, @Nullable String json, PrintStream out, PrintStream err) throws Exception {
        if (!"GET".equals(method) && !"POST".equals(method)) {
            err.println("--method must be GET or POST");
            return false;
        }
        if (json != null && !"POST".equals(method)) {
            err.println("--json requires --method=POST");
            return false;
        }
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
        RestClient.RequestBodySpec request = restClient
                .method("POST".equals(method) ? HttpMethod.POST : HttpMethod.GET)
                .uri(url)
                .header(X402PaymentInterceptor.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        ResponseEntity<String> response = request.retrieve().toEntity(String.class);

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

    /** Upper bound for a {@code --json-file} body; a question is a few hundred bytes. */
    static final int MAX_JSON_FILE_BYTES = 16 * 1024;

    /**
     * Picks the request body from {@code --json} or {@code --json-file} (at most one). A file body
     * is read as UTF-8, must be an absolute path to a regular file of at most {@value
     * #MAX_JSON_FILE_BYTES} bytes. Failure messages state the rule only, never the path's content
     * or the file's content.
     *
     * @return the body, or {@code null} if neither option was given
     * @throws IllegalArgumentException if both are given or the file is not acceptable
     */
    static @Nullable String resolveJsonBody(@Nullable String inlineJson, @Nullable String jsonFile) {
        if (inlineJson != null && jsonFile != null) {
            throw new IllegalArgumentException("use either --json or --json-file, not both");
        }
        if (jsonFile == null) {
            return inlineJson;
        }
        Path path = Path.of(jsonFile);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("--json-file must be an absolute path");
        }
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_JSON_FILE_BYTES) {
                throw new IllegalArgumentException(
                        "--json-file must be a regular file of at most " + MAX_JSON_FILE_BYTES + " bytes");
            }
            // Strict decoding: malformed UTF-8 is an error rather than silently replaced.
            byte[] bytes = Files.readAllBytes(path);
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (IOException e) {
            throw new IllegalArgumentException("--json-file could not be read as UTF-8");
        }
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
