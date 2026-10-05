package io.github.orhanyarkin.saiman.orchestrator.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NullabilityModelConverterTests {

    enum Colour {
        RED,
        BLUE
    }

    record Inner(String name) {}

    record Sample(
            String always,
            long count,
            @Nullable String maybe,
            @Nullable Long maybeNumber,
            Inner inner,
            Sample.@Nullable Inner2 maybeInner,
            Inner viaOther,
            @Nullable Colour maybeColour) {
        record Inner2(int x) {}
    }

    private JsonNode schemaOf(String name) throws Exception {
        ModelConverters converters = new ModelConverters(true);
        converters.addConverter(new NullabilityModelConverter());
        Map<String, Schema> all = converters.readAll(Sample.class);
        return JsonMapper.builder().build().readTree(Json31.mapper().writeValueAsString(all.get(name)));
    }

    @Test
    void nonNullComponentsAreRequiredAndNullableOnesAreNot() throws Exception {
        JsonNode sample = schemaOf("Sample");
        assertThat(sample.get("required").valueStream().map(JsonNode::asString))
                .containsExactlyInAnyOrder("always", "count", "inner", "viaOther");
    }

    @Test
    void aNullableScalarGetsATypeArrayWithNull() throws Exception {
        JsonNode props = schemaOf("Sample").get("properties");
        assertThat(props.get("maybe").get("type").valueStream().map(JsonNode::asString))
                .containsExactly("string", "null");
        assertThat(props.get("maybeNumber").get("type").valueStream().map(JsonNode::asString))
                .containsExactly("integer", "null");
        assertThat(props.get("always").get("type").asString()).isEqualTo("string");
    }

    @Test
    void aNullableReferenceBecomesOneOfRefAndNull() throws Exception {
        JsonNode props = schemaOf("Sample").get("properties");
        JsonNode oneOf = props.get("maybeInner").get("oneOf");
        assertThat(oneOf).hasSize(2);
        assertThat(oneOf.get(0).get("$ref").asString()).endsWith("/Inner2");
        assertThat(oneOf.get(1).get("type").asString()).isEqualTo("null");
        assertThat(props.get("inner").has("oneOf")).isFalse();
    }

    @Test
    void aNullableEnumAllowsNull() throws Exception {
        JsonNode colour = schemaOf("Sample").get("properties").get("maybeColour");
        String json = colour.toString();
        assertThat(json).contains("\"null\"");
    }

    @Test
    void nestedRecordsAreRequiredToo() throws Exception {
        assertThat(schemaOf("Inner").get("required").valueStream().map(JsonNode::asString))
                .containsExactly("name");
    }
}
