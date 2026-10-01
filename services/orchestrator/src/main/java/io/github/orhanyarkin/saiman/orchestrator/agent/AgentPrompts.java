package io.github.orhanyarkin.saiman.orchestrator.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The agents' prompts, from {@code src/main/resources/prompts/*.st} (StringTemplate, rendered by
 * Spring AI's {@link PromptTemplate}). Rendering happens here, once per call, so the {@code
 * ChatClient} receives finished text and never re-renders untrusted values as a template.
 *
 * <p>The JSON output formats come from Spring AI's {@link BeanOutputConverter} (a JSON schema
 * generated from the output records); parsing and validation is our own strict code ({@link
 * AgentOutputs}), not the converter's lenient binding.
 */
@Component
public class AgentPrompts {

    /** Output shape of the planner. */
    record ResearchPlan(List<String> tickers, List<String> tasks) {}

    /** Output shape of the risk step. */
    record RiskAssessment(List<Risk> risks) {}

    /** One risk as the risk step writes it. */
    record Risk(String title, String severity, List<String> citedChunkIds) {}

    /** Output shape of the synthesis step. */
    record Synthesis(String answer, List<String> citedChunkIds) {}

    private final String systemRules;
    private final String planner;
    private final String researcher;
    private final String risk;
    private final String synthesis;
    private final String planFormat;
    private final String riskFormat;
    private final String synthesisFormat;

    public AgentPrompts() {
        this.systemRules = read("system-rules");
        this.planner = read("planner");
        this.researcher = read("researcher");
        this.risk = read("risk");
        this.synthesis = read("synthesis");
        this.planFormat = new BeanOutputConverter<>(ResearchPlan.class).getFormat();
        this.riskFormat = new BeanOutputConverter<>(RiskAssessment.class).getFormat();
        this.synthesisFormat = new BeanOutputConverter<>(Synthesis.class).getFormat();
    }

    /** The system prompt of every step: the rules no conversation content can change. */
    String system() {
        return systemRules;
    }

    String planner(String question, String tickers) {
        return render(planner, Map.of("question", question, "tickers", tickers, "format", planFormat));
    }

    String researcher(String question, String plan, int maxToolCalls) {
        return render(researcher, Map.of("question", question, "plan", plan, "maxToolCalls", maxToolCalls));
    }

    String risk(String question, String notes, String evidence) {
        return render(risk, Map.of("question", question, "notes", notes, "evidence", evidence, "format", riskFormat));
    }

    String synthesis(String question, String notes, String risks, String evidence) {
        return render(
                synthesis,
                Map.of(
                        "question",
                        question,
                        "notes",
                        notes,
                        "risks",
                        risks,
                        "evidence",
                        evidence,
                        "format",
                        synthesisFormat));
    }

    private static String render(String template, Map<String, Object> variables) {
        return PromptTemplate.builder()
                .template(template)
                .variables(variables)
                .build()
                .render();
    }

    private static String read(String name) {
        try {
            return new ClassPathResource("prompts/" + name + ".st").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing prompt " + name, e);
        }
    }
}
