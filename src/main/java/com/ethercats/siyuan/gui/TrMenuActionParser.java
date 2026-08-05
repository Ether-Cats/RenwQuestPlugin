package com.ethercats.siyuan.gui;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the subset of TrMenu actions supported by the Siyuan menu runtime. */
final class TrMenuActionParser {
    private TrMenuActionParser() {
    }

    static ClickActions parse(Object configured) {
        Map<String, Object> values = sectionValues(configured);
        if (hasClickActions(values)) {
            return new ClickActions(
                parseActions(value(values, "left")),
                parseActions(value(values, "right")),
                parseActions(value(values, "all"))
            );
        }
        return new ClickActions(List.of(), List.of(), parseActions(configured));
    }

    private static boolean hasClickActions(Map<String, Object> values) {
        return value(values, "left") != null || value(values, "right") != null || value(values, "all") != null;
    }

    private static List<String> parseActions(Object configured) {
        List<String> actions = new ArrayList<>();
        collectActions(configured, actions);
        return MenuActionCodec.fromDeluxe(actions);
    }

    private static void collectActions(Object configured, List<String> actions) {
        if (configured instanceof String action) {
            if (!action.isBlank()) actions.add(action);
            return;
        }
        if (configured instanceof List<?> values) {
            for (Object value : values) {
                collectActions(value, actions);
            }
            return;
        }

        Object catchers = value(sectionValues(configured), "catcher");
        if (catchers != null) actions.addAll(parseCatchers(catchers));
    }

    private static List<String> parseCatchers(Object configured) {
        List<String> actions = new ArrayList<>();
        for (Map.Entry<String, Object> entry : sectionValues(configured).entrySet()) {
            String id = entry.getKey().trim();
            if (id.isBlank()) continue;

            Map<String, Object> catcher = sectionValues(entry.getValue());
            if (catcher.isEmpty()) continue;

            StringBuilder action = new StringBuilder("catcher:").append(id);
            appendSegments(action, "start", parseActions(value(catcher, "start")));
            appendSegments(action, "cancel", parseActions(value(catcher, "cancel")));
            appendSegments(action, "end", extractEndActions(value(catcher, "end")));
            actions.add(action.toString());
        }
        return actions;
    }

    private static List<String> extractEndActions(Object configured) {
        List<String> direct = parseActions(configured);
        if (!direct.isEmpty()) return direct;

        if (configured instanceof List<?> values) {
            List<String> actions = new ArrayList<>();
            for (Object value : values) {
                actions.addAll(extractEndActions(value));
            }
            return actions;
        }

        Map<String, Object> values = sectionValues(configured);
        Object actions = value(values, "actions");
        if (actions != null) return parseActions(actions);
        Object action = value(values, "action");
        return action == null ? List.of() : parseActions(action);
    }

    private static void appendSegments(StringBuilder action, String key, List<String> values) {
        for (String value : values) {
            action.append('|').append(key).append('=').append(value);
        }
    }

    private static Object value(Map<String, Object> values, String key) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private static Map<String, Object> sectionValues(Object configured) {
        Map<?, ?> source;
        if (configured instanceof ConfigurationSection section) {
            source = section.getValues(false);
        } else if (configured instanceof Map<?, ?> map) {
            source = map;
        } else {
            return Map.of();
        }

        Map<String, Object> values = new LinkedHashMap<>();
        source.forEach((key, value) -> values.put(String.valueOf(key), value));
        return values;
    }

    record ClickActions(List<String> left, List<String> right, List<String> all) {
        ClickActions {
            left = List.copyOf(left);
            right = List.copyOf(right);
            all = List.copyOf(all);
        }
    }
}
