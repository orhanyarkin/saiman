package io.github.orhanyarkin.saiman.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

class ModulithDefaultsTests {

    private final ModulithDefaultsEnvironmentPostProcessor processor = new ModulithDefaultsEnvironmentPostProcessor();

    @Test
    void applicationValuesWinOverDefaults() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(new MockPropertySource("app")
                        .withProperty("spring.modulith.events.completion-mode", "ARCHIVE")
                        .withProperty("spring.modulith.events.republish-outstanding-events-on-restart", "false"));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.modulith.events.completion-mode")).isEqualTo("ARCHIVE");
        assertThat(env.getProperty("spring.modulith.events.republish-outstanding-events-on-restart"))
                .isEqualTo("false");
        assertThat(env.getProperty("spring.modulith.events.registry-trigger-annotation"))
                .isEqualTo("org.springframework.modulith.events.ApplicationModuleListener");
    }

    @Test
    void appliedOnlyOnce() {
        StandardEnvironment env = new StandardEnvironment();
        processor.postProcessEnvironment(env, new SpringApplication());
        processor.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getPropertySources().stream()
                        .filter(s -> s.getName().equals(ModulithDefaultsEnvironmentPostProcessor.SOURCE_NAME))
                        .count())
                .isEqualTo(1);
    }
}
