package io.github.orhanyarkin.saiman.ledger.api;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Teaches springdoc the JSpecify nullness of the response records (ADR-0022): every record component is
 * {@code required} unless it is annotated {@link Nullable}, and a {@code @Nullable} one is rendered OpenAPI 3.1 style as
 * {@code type: [<t>, "null"]} ({@code oneOf: [{$ref}, {type: "null"}]} for a schema reference). Without this,
 * springdoc emits no {@code required} array and no null markers, so a generated TypeScript client would type every
 * property as optional and none as nullable.
 *
 * <p>Implemented as a swagger-core {@link ModelConverter}, not an {@code OpenApiCustomizer}: JSpecify's
 * {@code @Nullable} is a {@code TYPE_USE} annotation, which only the record component's {@code AnnotatedType} carries,
 * and only a model converter still knows the Java type behind a schema. springdoc registers every
 * {@code ModelConverter} bean in front of its own converters.
 */
public class NullabilityOpenApiCustomizer implements ModelConverter {

    private static final String NULL = "null";

    @Override
    public @Nullable Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        Schema resolved = chain.next().resolve(type, context, chain);
        if (resolved == null || type.getType() == null) {
            return resolved;
        }
        Class<?> raw = Json.mapper().constructType(type.getType()).getRawClass();
        if (!raw.isRecord()) {
            return resolved;
        }
        Schema model = model(resolved, context);
        if (model == null || model.getProperties() == null) {
            return resolved;
        }
        @SuppressWarnings("unchecked")
        Map<String, Schema> properties = model.getProperties();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : raw.getRecordComponents()) {
            String name = component.getName();
            Schema property = properties.get(name);
            if (property == null) {
                continue; // ignored by Jackson
            }
            if (isNullable(component)) {
                properties.put(name, nullable(property));
            } else {
                required.add(name);
            }
        }
        model.setRequired(required.isEmpty() ? null : required);
        return resolved;
    }

    @Override
    public boolean isOpenapi31() {
        return true;
    }

    private static boolean isNullable(RecordComponent component) {
        return component.getAnnotatedType().isAnnotationPresent(Nullable.class)
                || component.isAnnotationPresent(Nullable.class);
    }

    /** The schema itself, or the defined model a {@code $ref} points to. */
    private static @Nullable Schema model(Schema resolved, ModelConverterContext context) {
        String ref = resolved.get$ref();
        if (ref == null) {
            return resolved;
        }
        return context.getDefinedModels().get(ref.substring(ref.lastIndexOf('/') + 1));
    }

    @SuppressWarnings("unchecked")
    private static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            JsonSchema ref = new JsonSchema();
            ref.set$ref(property.get$ref());
            return new JsonSchema().oneOf(List.of(ref, new JsonSchema().types(new LinkedHashSet<>(List.of(NULL)))));
        }
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        types.add(NULL);
        property.setTypes(types);
        return property;
    }
}
