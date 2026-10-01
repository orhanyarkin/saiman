package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The run, event and tool tests that script the run's outcome directly: {@link RunHarness} plus a
 * {@link ScriptedPipeline} that is {@code @Primary}, so runs skip the agents unless a test calls
 * them. All these tests share one Spring context.
 */
@Import(RunTestSupport.ScriptedPipelineConfiguration.class)
public abstract class RunTestSupport extends RunHarness {

    @Autowired
    protected ScriptedPipeline pipeline;

    @BeforeEach
    void resetPipeline() {
        pipeline.reset();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedPipelineConfiguration {

        @Bean
        @Primary // wins over the real AgentPipeline: these tests script the run's outcome directly
        ScriptedPipeline scriptedPipeline() {
            return new ScriptedPipeline();
        }
    }
}
