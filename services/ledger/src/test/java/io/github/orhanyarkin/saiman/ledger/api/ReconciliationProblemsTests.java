package io.github.orhanyarkin.saiman.ledger.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationReports;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The POST's refusals go through {@link ApiExceptionHandler} like every other problem: fixed detail and the route
 * template as {@code instance}, never the request URI (the 429 with {@code Retry-After} is covered end to end in
 * {@code ReconciliationTests}). A standalone MockMvc, because the shared test context always has a chain client.
 */
class ReconciliationProblemsTests {

    private final ReconciliationService service = mock(ReconciliationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ReconciliationController(service, mock(ReconciliationReports.class), mock(BoundedReads.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void noChainClientIsAFixed503WithTheRouteAsInstance() throws Exception {
        when(service.start()).thenThrow(ReconciliationService.ChainNotConfiguredException.class);

        mvc.perform(post("/api/v1/reconciliation/runs?zzmark=1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("No Base Sepolia client is configured"))
                .andExpect(jsonPath("$.instance").value("/api/v1/reconciliation/runs"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void runInProgressIsAFixed409WithTheRouteAsInstance() throws Exception {
        when(service.start()).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/reconciliation/runs"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A reconciliation run is already in progress"))
                .andExpect(jsonPath("$.instance").value("/api/v1/reconciliation/runs"));
    }
}
