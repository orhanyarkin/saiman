package io.github.orhanyarkin.saiman.sellerapi.settlement;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * The HTTP server observation convention of seller-api: Spring's default, except that no key value of a credit-note
 * lookup carries the request path. That path is {@code /internal/credit-notes/<payment key>}, and the payment key is
 * {@code network:asset:payer:nonce}, which must not end up in traces ({@code http.url}) or anywhere else in telemetry
 * (milestone audit L1). Every key value that mentions {@code credit-notes} is replaced by the route template {@value
 * #TEMPLATE}, whatever the outcome (200, 404, the 401/403 of the security chain, a 400 from the firewall) and whatever
 * the path form (percent-encoded or not). Every other path is reported exactly as by the default convention.
 *
 * <p>Boot's MVC observation auto-configuration uses a {@code ServerRequestObservationConvention} bean when there is one.
 */
@Component
class InternalPathObservationConvention extends DefaultServerRequestObservationConvention {

    static final String TEMPLATE = "/internal/credit-notes/{paymentKey}";

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        KeyValues values = super.getLowCardinalityKeyValues(context);
        return isCreditNoteLookup(context) ? scrub(values) : values;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
        KeyValues values = super.getHighCardinalityKeyValues(context);
        return isCreditNoteLookup(context) ? scrub(values) : values;
    }

    @Override
    public @Nullable String getContextualName(ServerRequestObservationContext context) {
        String name = super.getContextualName(context);
        return name != null && mentionsCreditNotes(name) ? "http " + lowerMethod(context) + " " + TEMPLATE : name;
    }

    private static boolean isCreditNoteLookup(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        String uri = request == null ? null : request.getRequestURI();
        return uri != null && mentionsCreditNotes(decodeLoosely(uri));
    }

    private static KeyValues scrub(KeyValues values) {
        return KeyValues.of(values.stream()
                .map(kv -> mentionsCreditNotes(decodeLoosely(kv.getValue())) ? KeyValue.of(kv.getKey(), TEMPLATE) : kv)
                .toList());
    }

    /** Lower-cased with every {@code %xx} escape decoded once, so an encoded path form is recognised too. */
    private static String decodeLoosely(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.append((char) (hi * 16 + lo));
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean mentionsCreditNotes(String value) {
        return value.toLowerCase(Locale.ROOT).contains("credit-notes");
    }

    private static String lowerMethod(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        return request == null ? "unknown" : request.getMethod().toLowerCase(Locale.ROOT);
    }
}
