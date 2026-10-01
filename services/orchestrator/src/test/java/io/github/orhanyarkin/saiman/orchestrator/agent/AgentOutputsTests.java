package io.github.orhanyarkin.saiman.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.orchestrator.run.FailureCode;
import io.github.orhanyarkin.saiman.orchestrator.tool.EvidenceCitation;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Strict validation of the agents' structured answers and the hygiene of model text. */
class AgentOutputsTests {

    private static final Set<String> CATALOGUE = Set.of("THYAO", "ASELS", "GARAN", "AKBNK");
    private static final Map<String, EvidenceCitation> EVIDENCE = Map.of(
            "kap:1001:0001", new EvidenceCitation("kap:1001:0001", "https://www.kap.org.tr/tr/Bildirim/1001", "Report"),
            "kap:1002:0002", new EvidenceCitation("kap:1002:0002", null, null));

    private final AgentOutputs outputs = new AgentOutputs(JsonMapper.builder().build());

    private static FailureCode codeOf(Runnable action) {
        try {
            action.run();
        } catch (AgentOutputs.OutputException e) {
            return e.code();
        }
        throw new AssertionError("no OutputException");
    }

    // ---- plan ----

    @Test
    void aValidPlanIsNormalised() {
        AgentOutputs.Plan plan = outputs.plan(
                "```json\n{\"tickers\":[\"thyao\",\" ASELS \"],\"tasks\":[\"Read the\\nannual report\"]}\n```",
                CATALOGUE);

        assertThat(plan.tickers()).containsExactly("THYAO", "ASELS");
        assertThat(plan.tasks()).containsExactly("Read the annual report");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"tickers\":[\"EVIL1\"],\"tasks\":[\"t\"]}", // not in the catalogue
                "{\"tickers\":[\"THYAO\",\"ASELS\",\"GARAN\",\"AKBNK\"],\"tasks\":[\"t\"]}", // more than three
                "{\"tickers\":[],\"tasks\":[\"t\"]}", // none
                "{\"tickers\":[\"THYAO\",\"THYAO\"],\"tasks\":[\"t\"]}", // duplicate
                "{\"tickers\":[\"http://x\"],\"tasks\":[\"t\"]}", // not a ticker
                "{\"tickers\":[\"THYAO\"],\"tasks\":[]}", // no task
                "{\"tickers\":[\"THYAO\"],\"tasks\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}", // more than four tasks
                "{\"tickers\":[\"THYAO\"],\"tasks\":[\"   \"]}" // an empty task
            })
    void aPlanBreakingARuleIsAnInvalidPlan(String json) {
        assertThat(codeOf(() -> outputs.plan(json, CATALOGUE))).isEqualTo(FailureCode.INVALID_PLAN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json at all",
                "[\"THYAO\"]",
                "{\"tickers\":\"THYAO\",\"tasks\":[\"t\"]}",
                "{\"tickers\":[1],\"tasks\":[\"t\"]}",
                "{\"tickers\":[\"THYAO\"]}",
                "{\"tickers\":[\"THYAO\"],\"tasks\":[\"t\"],\"budget\":999999}",
                "{\"tickers\":[\"THYAO\"],\"tickers\":[\"ASELS\"],\"tasks\":[\"t\"]}"
            })
    void aMalformedPlanIsInvalidModelOutput(String json) {
        assertThat(codeOf(() -> outputs.plan(json, CATALOGUE))).isEqualTo(FailureCode.INVALID_MODEL_OUTPUT);
    }

    @Test
    void aNullOrHugeAnswerIsInvalidModelOutput() {
        assertThat(codeOf(() -> outputs.plan(null, CATALOGUE))).isEqualTo(FailureCode.INVALID_MODEL_OUTPUT);
        String huge = "{\"tickers\":[\"THYAO\"],\"tasks\":[\"" + "x".repeat(AgentOutputs.MAX_OUTPUT_CHARS) + "\"]}";
        assertThat(codeOf(() -> outputs.plan(huge, CATALOGUE))).isEqualTo(FailureCode.INVALID_MODEL_OUTPUT);
    }

    // ---- report and citations ----

    @Test
    void citationsAreRebuiltFromTheEvidenceAndInvalidIdsDropped() {
        RunEventData.Report report = outputs.report(
                "{\"answer\":\"Fine.\",\"citedChunkIds\":[\"kap:1002:0002\",\"kap:7777:0001\",\"kap:1001:0001\","
                        + "\"kap:1002:0002\",\"javascript:alert(1)\"]}",
                AgentOutputsTests::lookup);

        assertThat(report.citations())
                .containsExactly(
                        new RunEventData.Citation(
                                "kap:1002:0002", "https://www.kap.org.tr/tr/Bildirim/1002", "KAP disclosure 1002"),
                        new RunEventData.Citation(
                                "kap:1001:0001", "https://www.kap.org.tr/tr/Bildirim/1001", "Report"));
    }

    @Test
    void zeroValidCitationsIsNoValidCitations() {
        assertThat(codeOf(() -> outputs.report(
                        "{\"answer\":\"Fine.\",\"citedChunkIds\":[\"kap:7777:0001\"]}", AgentOutputsTests::lookup)))
                .isEqualTo(FailureCode.NO_VALID_CITATIONS);
        assertThat(codeOf(
                        () -> outputs.report("{\"answer\":\"Fine.\",\"citedChunkIds\":[]}", AgentOutputsTests::lookup)))
                .isEqualTo(FailureCode.NO_VALID_CITATIONS);
    }

    @Test
    void anEmptyAnswerIsInvalidModelOutput() {
        assertThat(codeOf(() -> outputs.report(
                        "{\"answer\":\" \",\"citedChunkIds\":[\"kap:1001:0001\"]}", AgentOutputsTests::lookup)))
                .isEqualTo(FailureCode.INVALID_MODEL_OUTPUT);
    }

    @Test
    void theAnswerIsPlainTextWithoutLinksAndCapped() {
        int[] bidi = {0x202E};
        String answer = "See [the filing](https://evil.example/steal) and http://x.y/z or www.evil.com/a, evil.com too "
                + "<script>alert(1)</script>" + new String(bidi, 0, 1) + " end";
        RunEventData.Report report = outputs.report(
                "{\"answer\":" + quote(answer) + ",\"citedChunkIds\":[\"kap:1001:0001\"]}", AgentOutputsTests::lookup);

        assertThat(report.answer())
                .doesNotContain("http")
                .doesNotContain("www.")
                .doesNotContain("evil.com")
                .doesNotContain("<")
                .doesNotContain(">")
                .doesNotContain(new String(bidi, 0, 1))
                .contains("See the filing and [link removed]")
                .endsWith("end");

        String long_ = "a".repeat(AgentOutputs.MAX_ANSWER + 100);
        assertThat(outputs.report(
                                "{\"answer\":\"" + long_ + "\",\"citedChunkIds\":[\"kap:1001:0001\"]}",
                                AgentOutputsTests::lookup)
                        .answer())
                .hasSize(AgentOutputs.MAX_ANSWER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://x", "evil.ai/x", "1.2.3.4/x", "www.evil.com", "hxxp://evil"})
    void aCitationTitleWithALinkIsScrubbed(String link) {
        RunEventData.Citation citation =
                AgentOutputs.citation(new EvidenceCitation("kap:7:0001", null, "Report " + link + " end"));

        assertThat(citation.title()).isEqualTo("Report [link removed] end");
        assertThat(AgentOutputs.citation(new EvidenceCitation("kap:7:0001", null, "https://x"))
                        .title())
                .isEqualTo("[link removed]");
    }

    // ---- risks ----

    @Test
    void risksKeepOnlyKnownChunkIdsAndFixedSeverities() {
        var risks = outputs.risks(
                "{\"risks\":[{\"title\":\"FX risk\",\"severity\":\"high\",\"citedChunkIds\":[\"kap:1001:0001\",\"kap:9:0001\"]}]}",
                AgentOutputsTests::lookup);

        assertThat(risks).containsExactly(new AgentOutputs.Risk("FX risk", "HIGH", java.util.List.of("kap:1001:0001")));
        assertThatThrownBy(() -> outputs.risks(
                        "{\"risks\":[{\"title\":\"x\",\"severity\":\"CRITICAL\",\"citedChunkIds\":[]}]}",
                        AgentOutputsTests::lookup))
                .isInstanceOf(AgentOutputs.OutputException.class);
    }

    private static Optional<EvidenceCitation> lookup(String id) {
        return Optional.ofNullable(EVIDENCE.get(id));
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.codePoints().forEach(cp -> {
            if (cp == '"' || cp == '\\') {
                out.append('\\').appendCodePoint(cp);
            } else if (cp > 0x7e) {
                out.append(String.format("\\u%04x", cp));
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.append('"').toString();
    }
}
