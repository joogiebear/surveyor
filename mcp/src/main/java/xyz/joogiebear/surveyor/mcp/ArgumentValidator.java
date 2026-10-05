package xyz.joogiebear.surveyor.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/**
 * Checks tool arguments against the tool's input schema: required names, no unknown names, and the
 * types the catalogue uses (string, integer, boolean, array of integers, enum). Messages say what
 * to change, because an agent reads them.
 */
public final class ArgumentValidator {

    private static final int MAX_STRING = 4096;

    private ArgumentValidator() {
    }

    /** @return null if the arguments fit, otherwise the first problem */
    public static String validate(JsonObject schema, JsonObject arguments) {
        JsonObject props = schema.getAsJsonObject("properties");
        for (Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
            if (!props.has(entry.getKey())) {
                return "Unknown argument '" + entry.getKey() + "'. Allowed: " + String.join(", ", props.keySet()) + ".";
            }
            String problem = check(entry.getKey(), props.getAsJsonObject(entry.getKey()), entry.getValue());
            if (problem != null) {
                return problem;
            }
        }
        if (schema.has("required")) {
            for (JsonElement name : schema.getAsJsonArray("required")) {
                JsonElement value = arguments.get(name.getAsString());
                if (value == null || value.isJsonNull()) {
                    return "Missing required argument '" + name.getAsString() + "'.";
                }
            }
        }
        return null;
    }

    private static String check(String name, JsonObject spec, JsonElement value) {
        if (value.isJsonNull()) {
            return "Argument '" + name + "' must not be null; leave it out instead.";
        }
        String type = spec.get("type").getAsString();
        switch (type) {
            case "string" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    return "Argument '" + name + "' must be a string.";
                }
                String text = value.getAsString();
                if (text.length() > MAX_STRING) {
                    return "Argument '" + name + "' is longer than " + MAX_STRING + " characters.";
                }
                if (spec.has("enum") && !contains(spec.getAsJsonArray("enum"), text)) {
                    return "Argument '" + name + "' must be one of " + spec.getAsJsonArray("enum") + ".";
                }
            }
            case "integer" -> {
                if (!isInt(value)) {
                    return "Argument '" + name + "' must be a whole number that fits in 32 bits.";
                }
            }
            case "boolean" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    return "Argument '" + name + "' must be true or false.";
                }
            }
            case "array" -> {
                if (!value.isJsonArray()) {
                    return "Argument '" + name + "' must be an array.";
                }
                JsonArray array = value.getAsJsonArray();
                if (array.size() < spec.get("minItems").getAsInt() || array.size() > spec.get("maxItems").getAsInt()) {
                    return "Argument '" + name + "' must have " + spec.get("minItems").getAsInt() + " entries, as [x, y, z].";
                }
                for (JsonElement item : array) {
                    if (!isInt(item)) {
                        return "Argument '" + name + "' must contain whole numbers only.";
                    }
                }
            }
            default -> throw new IllegalStateException("schema type " + type + " is not handled for " + name);
        }
        return null;
    }

    private static boolean isInt(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        JsonPrimitive number = value.getAsJsonPrimitive();
        double d = number.getAsDouble();
        return d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
    }

    private static boolean contains(JsonArray values, String text) {
        for (JsonElement value : values) {
            if (value.getAsString().equals(text)) {
                return true;
            }
        }
        return false;
    }
}
