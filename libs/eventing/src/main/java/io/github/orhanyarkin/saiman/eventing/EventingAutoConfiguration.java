package io.github.orhanyarkin.saiman.eventing;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Provides the {@link InboxGuard} once a {@link JdbcClient} exists; applications can replace it. */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration")
public class EventingAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcClient.class)
    @ConditionalOnMissingBean(InboxGuard.class)
    InboxGuard inboxGuard(JdbcClient jdbc) {
        return new JdbcInboxGuard(jdbc);
    }
}
