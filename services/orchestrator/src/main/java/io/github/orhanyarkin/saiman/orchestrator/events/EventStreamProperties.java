package io.github.orhanyarkin.saiman.orchestrator.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.orchestrator.events.*}: the SSE run stream.
 *
 * @param heartbeat interval of the {@code :heartbeat} comment that keeps proxies from closing an
 *     idle stream
 * @param timeout the longest one stream stays open (a run that waits for an approval can be long)
 * @param maxStreamsPerRun concurrent streams of one run
 * @param maxStreams concurrent streams in total; each holds one virtual thread
 */
@ConfigurationProperties("saiman.orchestrator.events")
public record EventStreamProperties(
        @DefaultValue("15s") Duration heartbeat,
        @DefaultValue("30m") Duration timeout,
        @DefaultValue("4") int maxStreamsPerRun,
        @DefaultValue("64") int maxStreams) {

    public EventStreamProperties {
        if (heartbeat.isNegative() || heartbeat.isZero() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("saiman.orchestrator.events durations must be positive");
        }
        if (maxStreamsPerRun <= 0 || maxStreams <= 0) {
            throw new IllegalArgumentException("saiman.orchestrator.events stream limits must be positive");
        }
    }
}
