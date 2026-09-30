package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The free catalogue, {@code GET /v1/tickers}: which tickers the paid endpoints can answer about,
 * so a buyer agent can plan before it pays. Deliberately not {@code @RequiresPayment}; it costs no
 * model call and, thanks to the catalogue's cache, at most one ingest call per TTL.
 */
@RestController
class TickerController {

    private static final CacheControl CACHE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final TickerCatalog catalog;

    TickerController(TickerCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/v1/tickers")
    ResponseEntity<TickerListResponse> tickers() {
        return ResponseEntity.ok().cacheControl(CACHE).body(catalog.tickers());
    }
}
