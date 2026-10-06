package io.github.orhanyarkin.saiman.apisecurity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

class RedactingBearerTokenConverterTests {

    private final RedactingBearerTokenConverter converter = new RedactingBearerTokenConverter();

    @Test
    void theAuthenticationRequestNeverExposesTheRawToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/runs");
        request.addHeader(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.OPERATOR));

        Authentication authentication = converter.convert(request);

        assertThat(authentication).isInstanceOf(BearerTokenAuthenticationToken.class);
        // The verifier still gets the token through getToken() ...
        assertThat(((BearerTokenAuthenticationToken) authentication).getToken()).isEqualTo(TestTokens.OPERATOR);
        // ... and nothing a log line or listener would print contains it.
        assertThat(authentication.getName()).isEqualTo("[PROTECTED]");
        assertThat(authentication.getPrincipal()).isEqualTo("[PROTECTED]");
        assertThat(authentication.getCredentials()).isEqualTo("[PROTECTED]");
        assertThat(authentication.toString()).doesNotContain(TestTokens.OPERATOR);
        assertThat(String.valueOf(authentication.getDetails())).doesNotContain(TestTokens.OPERATOR);
    }

    /** What the redaction is for: Spring's own request token is named after the raw token. */
    @Test
    void springsOwnTokenWouldExposeIt() {
        assertThat(new BearerTokenAuthenticationToken(TestTokens.OPERATOR).getName())
                .isEqualTo(TestTokens.OPERATOR);
    }

    @Test
    void queryAndFormParametersAndOtherSchemesAreIgnored() {
        MockHttpServletRequest query = new MockHttpServletRequest("GET", "/api/v1/runs");
        query.setParameter("access_token", TestTokens.OPERATOR);
        query.setQueryString("access_token=" + TestTokens.OPERATOR);
        assertThat(converter.convert(query)).isNull();

        MockHttpServletRequest form = new MockHttpServletRequest("POST", "/api/v1/runs");
        form.setContentType("application/x-www-form-urlencoded");
        form.setParameter("access_token", TestTokens.OPERATOR);
        assertThat(converter.convert(form)).isNull();

        MockHttpServletRequest basic = new MockHttpServletRequest("GET", "/api/v1/runs");
        basic.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + TestTokens.OPERATOR);
        assertThat(converter.convert(basic)).isNull();
    }
}
