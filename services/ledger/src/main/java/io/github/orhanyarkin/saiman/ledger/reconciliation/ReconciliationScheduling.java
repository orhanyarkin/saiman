package io.github.orhanyarkin.saiman.ledger.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs reconciliation every {@code saiman.ledger.reconciliation.interval} (first run one interval after startup),
 * registered only when a Base Sepolia client bean exists. A {@link SchedulingConfigurer} rather than
 * {@code @Scheduled}: the decision needs the bean, and {@code @ConditionalOnBean} on an application configuration
 * is evaluated before the auto-configuration that creates the client.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ReconciliationScheduling implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduling.class);

    private final ReconciliationService service;
    private final ReconciliationProperties properties;

    public ReconciliationScheduling(ReconciliationService service, ReconciliationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!service.chainConfigured()) {
            log.info("No Base Sepolia client (saiman.chain.rpc-url): scheduled reconciliation is off");
            return;
        }
        registrar.addFixedDelayTask(
                new FixedDelayTask(this::runScheduled, properties.interval(), properties.interval()));
    }

    private void runScheduled() {
        try {
            if (service.runNow().isEmpty()) {
                log.info("Scheduled reconciliation skipped: a run is already in progress");
            }
        } catch (RuntimeException e) {
            log.error("Scheduled reconciliation failed", e);
        }
    }
}
