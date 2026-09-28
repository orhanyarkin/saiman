package io.github.orhanyarkin.x402.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Encodes and decodes the base64-JSON x402 v2 header values (specs/transports-v2/http.md), and
 * the plain JSON bodies the facilitator HTTP API exchanges (section 7).
 *
 * <p>Uses its own {@link JsonMapper} instances (Jackson 3, matching Spring Boot 4.1; ADR-0008),
 * independent of any {@code ObjectMapper} the host application configures, so that this starter's
 * wire format never drifts with application-level Jackson customisation. {@link #readJson} and
 * {@link #writeJson} let a facilitator client (M1 tasks T2/T3) reuse this same configuration for
 * {@code /verify}, {@code /settle} and {@code /supported} bodies instead of standing up a second
 * one.
 *
 * <p><b>Strictness is aimed at the payment-critical decode only.</b> Decoding a {@link
 * PaymentPayload} (the {@code PAYMENT-SIGNATURE} header, i.e. what a server receives from a
 * client) rejects unrecognised top-level JSON properties ({@link
 * DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES}) -- this is the one input this starter should
 * treat as untrusted and money-relevant. Every other decode ({@link PaymentRequired}, {@link
 * SettlementResponse}, {@link #readJson}) tolerates unrecognised properties: {@code
 * PaymentRequired} may come from a seller running a newer version of this starter, and rejecting
 * an otherwise-successful {@code /settle} response over one unknown field would turn a known
 * outcome into an ambiguous one. Both mappers otherwise share the same defensive settings:
 * duplicate JSON keys fail loudly ({@link StreamReadFeature#STRICT_DUPLICATE_DETECTION}), scalar
 * coercion is off ({@link MapperFeature#ALLOW_COERCION_OF_SCALARS}) and, because that feature
 * alone does not cover coercion *into* a {@code String}, an explicit coercion config also fails a
 * JSON number or boolean where a wire string is expected -- a JSON number can never silently
 * become a wire string like {@link Eip3009Authorization#value()}. A JSON float can't silently
 * truncate into an {@code int} ({@link DeserializationFeature#ACCEPT_FLOAT_AS_INT}), and
 * serialization omits null fields. The {@code extra}/{@code extensions}/{@code
 * extensionResponses} fields the spec reserves for extensibility are modelled as {@code Map} and
 * are inherently open regardless of mapper -- any key-value pairs they carry round-trip unchanged.
 *
 * <p><b>Abuse limits.</b> A header value longer than {@link #MAX_ENCODED_CHARS} is rejected before
 * it is even base64-decoded; the post-decode byte length is bounded by {@link #MAX_DECODED_BYTES}.
 *
 * <p><b>No payload in error messages, and no chained cause.</b> Every exception this class throws
 * carries a message that never includes the header value, its base64 decoding, or any parsed
 * field value (ADR-0006 amendment) -- at most the dotted path of *property names* Jackson was
 * looking at when it failed (never the values at those properties). The underlying Jackson
 * exception is deliberately not set as the cause: its own message may itself contain fragments of
 * the input. Thread-safe and stateless; one instance is enough for an application.
 */
public final class X402Codec {

    private static final Pattern SAFE_PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_]{1,64}");

    /** Maximum size, in bytes, of a base64-decoded x402 header value this codec will parse. */
    public static final int MAX_DECODED_BYTES = 16 * 1024;

    /**
     * Maximum length, in characters, of a base64-encoded x402 header value: {@code 4 *
     * ceil(MAX_DECODED_BYTES / 3)}, the exact encoded length of {@link #MAX_DECODED_BYTES} raw
     * bytes. Checked before decoding, so an oversized header is rejected without ever running
     * base64 decoding on it.
     */
    public static final int MAX_ENCODED_CHARS = 4 * ((MAX_DECODED_BYTES + 2) / 3);

    private final JsonMapper strictMapper;
    private final JsonMapper tolerantMapper;

    public X402Codec() {
        this.strictMapper = baseBuilder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        this.tolerantMapper = baseBuilder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private static JsonMapper.Builder baseBuilder() {
        return JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                // ALLOW_COERCION_OF_SCALARS alone does not stop a JSON number/boolean from being
                // coerced into a String field (Jackson treats "any scalar can become a String" as
                // a separate, more permissive rule) -- every wire string in this package (amount,
                // value, validAfter, validBefore, nonce, addresses, signatures...) must come from
                // an actual JSON string, so textual targets explicitly fail on non-string input.
                .withCoercionConfig(
                        LogicalType.Textual,
                        cfg -> cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .changeDefaultPropertyInclusion(
                        inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL));
    }

    /** Encodes a {@link PaymentRequired} for the {@code PAYMENT-REQUIRED} header. */
    public String encodePaymentRequired(PaymentRequired paymentRequired) {
        return encode(paymentRequired);
    }

    /** Decodes a {@code PAYMENT-REQUIRED} header value. Tolerates unrecognised properties. */
    public PaymentRequired decodePaymentRequired(String headerValue) {
        return decode(headerValue, PaymentRequired.class, tolerantMapper);
    }

    /** Encodes a {@link PaymentPayload} for the {@code PAYMENT-SIGNATURE} header. */
    public String encodePaymentPayload(PaymentPayload paymentPayload) {
        return encode(paymentPayload);
    }

    /**
     * Decodes a {@code PAYMENT-SIGNATURE} header value. The one decode in this class that rejects
     * unrecognised properties: this is client-submitted, payment-critical input.
     */
    public PaymentPayload decodePaymentPayload(String headerValue) {
        return decode(headerValue, PaymentPayload.class, strictMapper);
    }

    /** Encodes a {@link SettlementResponse} for the {@code PAYMENT-RESPONSE} header. */
    public String encodeSettlementResponse(SettlementResponse settlementResponse) {
        return encode(settlementResponse);
    }

    /**
     * Decodes a {@code PAYMENT-RESPONSE} header value. Tolerates unrecognised properties: a
     * decode failure here on an otherwise-successful response is an ambiguous money outcome.
     */
    public SettlementResponse decodeSettlementResponse(String headerValue) {
        return decode(headerValue, SettlementResponse.class, tolerantMapper);
    }

    /**
     * Deserializes a plain (non-base64) x402-adjacent JSON body, such as a facilitator {@code
     * /verify} or {@code /settle} response. Tolerates unrecognised properties, for the same
     * reason {@link #decodeSettlementResponse} does.
     */
    public <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            throw new X402CodecException("JSON body must not be empty");
        }
        try {
            return tolerantMapper.readValue(json, type);
        } catch (RuntimeException e) {
            throw new X402CodecException("JSON body is not a valid " + type.getSimpleName() + pathSuffix(e));
        }
    }

    /** Serializes a value (such as a facilitator {@code /verify} or {@code /settle} request). */
    public String writeJson(Object value) {
        try {
            return tolerantMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            throw new X402CodecException("failed to encode a value as JSON");
        }
    }

    private String encode(Object value) {
        byte[] json;
        try {
            json = tolerantMapper.writeValueAsBytes(value);
        } catch (RuntimeException e) {
            throw new X402CodecException("failed to encode an x402 wire object");
        }
        return Base64.getEncoder().encodeToString(json);
    }

    private <T> T decode(String headerValue, Class<T> type, JsonMapper mapper) {
        if (headerValue == null || headerValue.isEmpty()) {
            throw new X402CodecException("x402 header value must not be empty");
        }
        if (headerValue.length() > MAX_ENCODED_CHARS) {
            throw new X402CodecException("x402 header value exceeds the maximum encoded length");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(headerValue);
        } catch (IllegalArgumentException e) {
            throw new X402CodecException("x402 header value is not valid base64");
        }
        if (decoded.length > MAX_DECODED_BYTES) {
            throw new X402CodecException("x402 header value decodes to more than " + MAX_DECODED_BYTES + " bytes");
        }
        try {
            return mapper.readValue(decoded, type);
        } catch (RuntimeException e) {
            throw new X402CodecException("x402 header value is not a valid " + type.getSimpleName() + pathSuffix(e));
        }
    }

    /**
     * Renders the dotted path of *property names* (never values) Jackson was looking at when
     * {@code e} was thrown, e.g. {@code " (at payload.authorization.value)"}, or {@code ""} if
     * {@code e} carries no path (or isn't a Jackson exception at all).
     */
    private static String pathSuffix(RuntimeException e) {
        if (!(e instanceof JacksonException jacksonException)) {
            return "";
        }
        List<JacksonException.Reference> path = jacksonException.getPath();
        if (path.isEmpty()) {
            return "";
        }
        // Property names can come from the sender (an unknown property in a strict decode), so
        // only short identifier-like names are shown; anything else becomes "?" to keep CR/LF,
        // escape sequences and long attacker text out of logs and Problem Details.
        String dotted = path.stream()
                .map(ref ->
                        ref.getPropertyName() != null ? safeSegment(ref.getPropertyName()) : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
        return " (at " + dotted + ")";
    }

    private static String safeSegment(String propertyName) {
        return SAFE_PATH_SEGMENT.matcher(propertyName).matches() ? propertyName : "?";
    }
}
