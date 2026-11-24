package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a run request strictly (REQ-RUN-002). Jackson's data binding would quietly accept a missing
 * number as 0, {@code null} as 0, {@code "20"} or {@code 1.5} as 20 and 1, and an enum ordinal; each of
 * those is a request the visitor did not mean, so the body is read as plain JSON values and every field is
 * checked by its exact type. Unknown fields, trailing text and duplicate keys are refused.
 *
 * <p>Every refusal is an {@link InvalidRunRequestException} whose message is written here, so no
 * library text or class name can reach the client.
 */
final class RunRequestParser {

    private static final Set<String> FIELDS = Set.of("ledger", "threads", "faults", "transfers", "duplicatePercent");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private RunRequestParser() {}

    static RunRequest parse(byte[] body) {
        Map<?, ?> fields = readObject(body);
        for (Object name : fields.keySet()) {
            if (!FIELDS.contains(name)) {
                throw new InvalidRunRequestException("unknown field; allowed: "
                        + String.join(", ", FIELDS.stream().sorted().toList()));
            }
        }
        return new RunRequest(
                enumField(fields, "ledger", LedgerChoice.class),
                enumField(fields, "threads", ThreadMode.class),
                enumField(fields, "faults", FaultChoice.class),
                intField(fields, "transfers"),
                intField(fields, "duplicatePercent"));
    }

    private static Map<?, ?> readObject(byte[] body) {
        Object value;
        try {
            value = MAPPER.readValue(body, Object.class);
        } catch (JacksonException notJson) {
            throw new InvalidRunRequestException("the body is not valid JSON");
        }
        if (!(value instanceof Map<?, ?> object)) {
            throw new InvalidRunRequestException("the body must be a JSON object");
        }
        return object;
    }

    private static <E extends Enum<E>> E enumField(Map<?, ?> fields, String name, Class<E> type) {
        Object value = fields.get(name);
        if (!(value instanceof String text)) {
            throw new InvalidRunRequestException(name + " is required and must be a string");
        }
        try {
            return Enum.valueOf(type, text); // exact, case-sensitive
        } catch (IllegalArgumentException unknownName) {
            throw new InvalidRunRequestException(name + " is not one of the allowed values");
        }
    }

    /** Only a JSON integer that fits an int: not null, not a string, not 1.5, not a huge number. */
    private static int intField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        if (value instanceof Integer number) {
            return number;
        }
        throw new InvalidRunRequestException(name + " is required and must be an integer");
    }
}
