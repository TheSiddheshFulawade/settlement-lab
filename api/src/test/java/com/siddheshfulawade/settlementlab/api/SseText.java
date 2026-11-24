package com.siddheshfulawade.settlementlab.api;

import java.util.ArrayList;
import java.util.List;

/** Parses the text of a server-sent event stream into its complete events (comments ignored). */
final class SseText {

    record Event(String name, String id, List<String> dataLines) {
        String data() {
            return String.join("\n", dataLines);
        }
    }

    private SseText() {}

    static List<Event> parse(String raw) {
        List<Event> events = new ArrayList<>();
        String[] blocks = raw.split("(\r\n|\r|\n)(\r\n|\r|\n)", -1);
        // the last block has no blank line after it yet: it is incomplete unless the text ends with one
        int complete = raw.matches("(?s).*(\r\n\r\n|\r\r|\n\n)$") ? blocks.length : blocks.length - 1;
        for (int i = 0; i < complete; i++) {
            String name = null;
            String id = null;
            List<String> data = new ArrayList<>();
            for (String line : blocks[i].split("\r\n|\r|\n")) {
                if (line.startsWith(":") || line.isEmpty()) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "event" -> name = value;
                    case "id" -> id = value;
                    case "data" -> data.add(value);
                    default -> {}
                }
            }
            if (name != null || !data.isEmpty()) {
                events.add(new Event(name, id, data));
            }
        }
        return events;
    }
}
