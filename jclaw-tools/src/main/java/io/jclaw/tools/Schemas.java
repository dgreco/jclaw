package io.jclaw.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small builders for the JSON Schema fragments a tool publishes to the model.
 *
 * <p>Schemas are written by hand rather than derived from Java types. The schema is a prompt: the
 * wording of a description measurably changes how well a model uses a tool, and generating it from
 * a record would trade that control for a convenience nobody needs — there are a dozen tools, not
 * a thousand.
 */
public final class Schemas {

    private Schemas() {
    }

    /** An object schema with the given properties and required keys. */
    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        // Reject unexpected keys: a model improvising an extra argument should fail loudly rather
        // than have it silently ignored.
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * A free-form object property whose values are strings: a map the caller fills in.
     *
     * <p>{@code additionalProperties} is a schema rather than {@code false} here, which is the
     * opposite of {@link #object(Map, List)} and deliberately so: the keys are the caller's to
     * choose (environment variable names, say), and only their shape is fixed.
     */
    public static Map<String, Object> stringMap(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "object");
        property.put("description", description);
        property.put("additionalProperties", Map.of("type", "string"));
        return property;
    }

    /** A string property. */
    public static Map<String, Object> string(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    /** An integer property with bounds. */
    public static Map<String, Object> integer(String description, int minimum, int maximum) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        return property;
    }

    /** A boolean property. */
    public static Map<String, Object> bool(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "boolean");
        property.put("description", description);
        return property;
    }

    /** Ordered property map; insertion order is the order the model sees. */
    public static Map<String, Object> properties(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("properties requires alternating key/value arguments");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return properties;
    }

    /** An empty object schema, for tools taking no arguments. */
    public static Map<String, Object> noArguments() {
        return object(Map.of(), List.of());
    }
}
