package io.github.orhanyarkin.saiman.orchestrator.payment;

import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * The seller-api's paid endpoints. The path templates are code; callers only supply the template
 * variables, which are strictly percent-encoded (a {@code /} or {@code ..} in a value can never
 * change the path). No caller ever supplies a URL.
 */
// HttpMethod is effectively immutable and the variable lists are List.of(...).
@SuppressWarnings("ImmutableEnumChecker")
public enum SellerEndpoint {
    /** {@code GET /v1/disclosures/{ticker}/summary}. */
    DISCLOSURE_SUMMARY(HttpMethod.GET, "/v1/disclosures/{ticker}/summary", List.of("ticker")),

    /** {@code POST /v1/disclosures/{ticker}/questions} with a JSON body. */
    DISCLOSURE_QUESTION(HttpMethod.POST, "/v1/disclosures/{ticker}/questions", List.of("ticker"));

    private final HttpMethod method;
    private final String pathTemplate;
    private final List<String> variables;

    SellerEndpoint(HttpMethod method, String pathTemplate, List<String> variables) {
        this.method = method;
        this.pathTemplate = pathTemplate;
        this.variables = variables;
    }

    public HttpMethod method() {
        return method;
    }

    public String pathTemplate() {
        return pathTemplate;
    }

    /** The template variable names, all of which must be supplied. */
    public List<String> variables() {
        return variables;
    }
}
