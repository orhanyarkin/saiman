package io.github.orhanyarkin.saiman.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The converter on a sample record, with a private {@link ModelConverters} (never the global instance). */
class NullabilityOpenApiCustomizerTests {

    record Sample(
            String name,
            @Nullable String note,
            Nested nested,
            @Nullable Nested maybe,
            List<String> tags,
            @Nullable Instant at,
            int count,
            @Nullable Long block) {}

    record Nested(long value) {}

    /** Not a record: left alone. */
    static class Bean {
        public @Nullable String text;
    }

    private final Map<String, Schema> schemas = schemas(Sample.class);

    @Test
    void everyNonNullableComponentIsRequired() {
        assertThat(schemas.get("Sample").getRequired()).containsExactly("count", "name", "nested", "tags");
        assertThat(schemas.get("Nested").getRequired()).containsExactly("value");
    }

    @Test
    void nullableScalarsGetANullType() {
        Map<String, Schema> properties = properties("Sample");
        assertThat(properties.get("note").getTypes()).containsExactly("string", "null");
        assertThat(properties.get("at").getTypes()).containsExactly("string", "null");
        assertThat(properties.get("at").getFormat()).isEqualTo("date-time");
        assertThat(properties.get("block").getTypes()).containsExactly("integer", "null");
        assertThat(properties.get("name").getTypes()).containsExactly("string");
        assertThat(properties.get("count").getTypes()).containsExactly("integer");
    }

    @Test
    void aNullableReferenceBecomesOneOfTheReferenceAndNull() {
        Schema maybe = properties("Sample").get("maybe");
        assertThat(maybe.get$ref()).isNull();
        assertThat(maybe.getOneOf()).hasSize(2);
        assertThat(((Schema) maybe.getOneOf().get(0)).get$ref()).isEqualTo("#/components/schemas/Nested");
        assertThat(((Schema) maybe.getOneOf().get(1)).getTypes()).containsExactly("null");
        assertThat(properties("Sample").get("nested").get$ref()).isEqualTo("#/components/schemas/Nested");
    }

    @Test
    void rendersAsOpenApi31() throws Exception {
        String json = Json31.mapper().writeValueAsString(schemas.get("Sample"));
        assertThat(json)
                .contains("\"note\":{\"type\":[\"string\",\"null\"]}")
                .contains("\"oneOf\":[{\"$ref\":\"#/components/schemas/Nested\"},{\"type\":\"null\"}]")
                .contains("\"required\":[\"count\",\"name\",\"nested\",\"tags\"]");
    }

    @Test
    void classesThatAreNotRecordsAreLeftAlone() {
        assertThat(schemas(Bean.class).get("Bean").getRequired()).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Schema> properties(String model) {
        return schemas.get(model).getProperties();
    }

    private static Map<String, Schema> schemas(Class<?> type) {
        ModelConverters converters = new ModelConverters(true);
        converters.addConverter(new NullabilityOpenApiCustomizer());
        return converters.readAll(new AnnotatedType(type));
    }
}
