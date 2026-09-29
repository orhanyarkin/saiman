package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * No disclosure summary exists for the requested ticker.
 *
 * <p>Extends {@link ErrorResponseException} rather than being caught by a separate {@code
 * @RestControllerAdvice}: Spring MVC's built-in RFC 9457 support (enabled by {@code
 * spring.mvc.problemdetails.enabled=true}) renders any {@link ErrorResponseException} as a
 * {@code 404 application/problem+json} body on its own. Thrown from the handler method (after the
 * x402 starter's {@code RequiresPaymentInterceptor} already accepted the payment), so it is a
 * non-2xx response the starter never settles -- see {@code
 * X402SettlementFilter}/docs/design/m1-x402.md ("Server flow").
 */
final class TickerNotFoundException extends ErrorResponseException {

    TickerNotFoundException(String ticker) {
        super(HttpStatus.NOT_FOUND);
        setTitle("Ticker not found");
        setDetail("No disclosure summary available for ticker '" + ticker + "'");
    }
}
