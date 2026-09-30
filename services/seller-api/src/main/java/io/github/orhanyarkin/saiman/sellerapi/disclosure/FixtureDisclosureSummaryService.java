package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code classpath:fixtures/disclosures/*.json} once at construction time and serves those
 * fixed summaries from memory.
 *
 * <p>Public-data-shaped, clearly-marked fixture data (each summary text says so) for three BIST
 * tickers -- there is no live data source behind this in M1. M2 replaces this class with a
 * RAG-backed {@link DisclosureSummaryService} implementation over real KAP disclosures; the
 * controller and response shape are unaffected (docs/design/m1-x402.md, "seller-api").
 */
@Service
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "fixture", matchIfMissing = true)
class FixtureDisclosureSummaryService implements DisclosureSummaryService {

    /** Every fixture summary is marked with this, never read from the fixture file itself. */
    static final String DATA_SOURCE = "fixture";

    private static final String FIXTURE_LOCATION_PATTERN = "classpath:fixtures/disclosures/*.json";

    private final Map<String, DisclosureSummaryResponse> summariesByTicker;

    FixtureDisclosureSummaryService(JsonMapper jsonMapper) {
        this.summariesByTicker = loadFixtures(jsonMapper);
    }

    @Override
    public DisclosureSummaryResponse summaryFor(String ticker) {
        DisclosureSummaryResponse summary = summariesByTicker.get(ticker);
        if (summary == null) {
            throw new TickerNotFoundException(ticker);
        }
        return summary;
    }

    private static Map<String, DisclosureSummaryResponse> loadFixtures(JsonMapper jsonMapper) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(FIXTURE_LOCATION_PATTERN);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list disclosure summary fixtures", e);
        }
        Map<String, DisclosureSummaryResponse> loaded = new java.util.HashMap<>();
        for (Resource resource : resources) {
            Fixture fixture;
            try (InputStream in = resource.getInputStream()) {
                fixture = jsonMapper.readValue(in, Fixture.class);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read disclosure summary fixture: " + resource, e);
            }
            loaded.put(
                    fixture.ticker(),
                    new DisclosureSummaryResponse(
                            fixture.ticker(), fixture.summary(), fixture.citations(), DATA_SOURCE));
        }
        return Map.copyOf(loaded);
    }

    /** Wire shape of a fixture file; {@link #DATA_SOURCE} is never read from disk. */
    private record Fixture(String ticker, String summary, List<DisclosureSummaryResponse.Citation> citations) {}
}
