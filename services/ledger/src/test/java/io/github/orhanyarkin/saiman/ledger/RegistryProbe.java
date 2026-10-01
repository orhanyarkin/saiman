package io.github.orhanyarkin.saiman.ledger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A plain in-process {@link TransactionalEventListener}, the kind ADR-0016 keeps out of Spring Modulith's
 * publication registry (registry trigger annotation = {@code @ApplicationModuleListener}, set by libs/eventing).
 * Part of every integration context so the scope test does not need a context of its own.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RegistryProbe {

    /** A test-only event. */
    public record Ping(String id) {}

    private final List<String> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void on(Ping ping) {
        received.add(ping.id());
    }

    public List<String> received() {
        return received;
    }
}
