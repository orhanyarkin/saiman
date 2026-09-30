package io.github.orhanyarkin.saiman.modelrouter;

import java.time.Duration;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Runs in a child JVM of {@link OpenAiEgressTests}: makes one chat call through the production
 * factory, with the process environment prepared by the parent (OPENAI_BASE_URL pointing at a local
 * server, HTTPS proxy pointing at a local rejecting proxy). Prints nothing that contains the key.
 */
public final class EgressProbeMain {

    private EgressProbeMain() {}

    public static void main(String[] args) {
        var credentials = new RouterProperties.OpenAi(args[0], 0, Duration.ofSeconds(5));
        var route = RouterProperties.defaults().routes().get(Tier.TIER0);
        try {
            new OpenAiModelFactory(credentials).chatModel(route).call(new Prompt("probe"));
        } catch (RuntimeException expected) {
            // the proxy refuses the tunnel; only where the client tried to connect matters
        }
        System.out.println("probe finished");
    }
}
