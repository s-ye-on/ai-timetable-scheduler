package me.timetablescheduler.domain.llm.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class EvaluationSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final ObjectMapper STRICT = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private EvaluationSupport() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    static String text(Object value) { return value instanceof String s ? s : ""; }
    static Map<String, Object> read(String json) {
        Object value = STRICT.readValue(json, Object.class);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON_OBJECT_REQUIRED");
        return map(value);
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }

    /** 현재 서비스가 전송한 스키마의 하위집합만 검증하며, 모르는 키워드는 통과시키지 않는다. */
    static List<String> schemaErrors(Map<String, Object> schema, Object actual) {
        List<String> errors = new ArrayList<>();
        validate(schema, actual, "$", errors);
        return errors;
    }

    private static void validate(Map<String, Object> s, Object value, String path, List<String> errors) {
        Set<String> supported = Set.of("type", "enum", "anyOf", "required", "properties", "additionalProperties", "description", "title");
        if (!supported.containsAll(s.keySet())) { errors.add(path + ":UNSUPPORTED_SCHEMA_KEYWORD"); return; }
        if (s.containsKey("anyOf")) {
            boolean valid = list(s.get("anyOf")).stream().anyMatch(option -> schemaErrors(map(option), value).isEmpty());
            if (!valid) errors.add(path + ":ANY_OF");
            return;
        }
        List<Object> types = s.get("type") instanceof List<?> ? list(s.get("type")) : List.of(s.getOrDefault("type", ""));
        boolean matches = types.stream().anyMatch(t -> switch (text(t)) {
            case "null" -> value == null;
            case "object" -> value instanceof Map<?, ?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Number n && new java.math.BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0;
            default -> false;
        });
        if (!matches) { errors.add(path + ":TYPE"); return; }
        if (s.containsKey("enum") && !list(s.get("enum")).contains(value)) errors.add(path + ":ENUM");
        if (value instanceof Map<?, ?>) {
            Map<String, Object> object = map(value), properties = map(s.get("properties"));
            for (Object required : list(s.get("required"))) {
                if (!object.containsKey(text(required))) errors.add(path + "." + required + ":MISSING");
            }
            for (var entry : object.entrySet()) {
                if (properties.containsKey(entry.getKey())) validate(map(properties.get(entry.getKey())), entry.getValue(), path + "." + entry.getKey(), errors);
                else if (Boolean.FALSE.equals(s.get("additionalProperties"))) errors.add(path + "." + entry.getKey() + ":ADDITIONAL_PROPERTY");
            }
        }
    }
}
