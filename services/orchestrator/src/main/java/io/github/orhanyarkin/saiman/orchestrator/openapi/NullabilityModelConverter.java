package io.github.orhanyarkin.saiman.orchestrator.openapi;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Teaches springdoc what JSpecify means. springdoc reads declaration annotations through Jackson, but
 * {@code org.jspecify.annotations.Nullable} is a type-use annotation, so it never shows up there: a
 * generated client would see every property as optional. This converter wraps the normal resolution
 * and, for every record, marks each component {@code required} unless it is {@code @Nullable}, and
 * renders the {@code @Nullable} ones the OpenAPI 3.1 way: {@code type: [<t>, "null"]}, or for a
 * {@code $ref} {@code oneOf: [{$ref}, {type: "null"}]}.
 *
 * <p>It works on the model definitions (the schema registered in the converter context), not on the
 * {@code $ref} returned to the caller, and is idempotent: a type met twice is changed once.
 */
public class NullabilityModelConverter implements ModelConverter {

    private static final String NULL = "null";
    private static final String REF_PREFIX = "#/components/schemas/";

    @Override
    public boolean isOpenapi31() {
        return true;
    }

    @Override
    @SuppressWarnings("rawtypes") // the swagger-core API is raw
    public @Nullable Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema<?> resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (resolved == null) {
            return null;
        }
        Class<?> raw = rawClass(type.getType());
        if (raw == null || !raw.isRecord()) {
            return resolved;
        }
        Schema<?> definition = resolved;
        String ref = resolved.get$ref();
        if (ref != null && ref.startsWith(REF_PREFIX)) {
            definition = context.getDefinedModels().get(ref.substring(REF_PREFIX.length()));
        }
        if (definition != null) {
            apply(raw, definition);
        }
        return resolved;
    }

    private static @Nullable Class<?> rawClass(@Nullable Type type) {
        return switch (type) {
            case JavaType javaType -> javaType.getRawClass();
            case Class<?> clazz -> clazz;
            case ParameterizedType parameterized -> parameterized.getRawType() instanceof Class<?> c ? c : null;
            case null, default -> null;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void apply(Class<?> record, Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        List<String> required = new ArrayList<>(schema.getRequired() == null ? List.of() : schema.getRequired());
        for (RecordComponent component : record.getRecordComponents()) {
            String name = component.getName();
            Schema property = properties.get(name);
            if (property == null) {
                continue;
            }
            if (component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                required.remove(name);
                properties.put(name, nullable(property));
            } else if (!required.contains(name)) {
                required.add(name);
            }
        }
        schema.setRequired(required.isEmpty() ? null : required);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            Schema wrapper = new Schema();
            wrapper.setOneOf(List.of(property, nullType()));
            return wrapper;
        }
        if (property.getOneOf() != null && property.getOneOf().stream().anyMatch(NullabilityModelConverter::isNull)) {
            return property;
        }
        if (property.getTypes() == null && property.getType() != null) {
            property.addType(property.getType());
        }
        if (property.getTypes() == null || !property.getTypes().contains(NULL)) {
            property.addType(NULL);
        }
        if (property.getEnum() != null && !property.getEnum().contains(null)) {
            property.getEnum().add(null); // an enum that omits null would reject the null the type allows
        }
        return property;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullType() {
        Schema schema = new Schema();
        schema.addType(NULL);
        return schema;
    }

    private static boolean isNull(Object schema) {
        return schema instanceof Schema<?> s
                && s.getTypes() != null
                && s.getTypes().contains(NULL);
    }
}
