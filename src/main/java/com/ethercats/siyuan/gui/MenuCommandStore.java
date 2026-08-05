package com.ethercats.siyuan.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tracks dynamically registered values by the menu that owns them. */
final class MenuCommandStore<T> {
    private final Map<String, T> commands = new LinkedHashMap<>();
    private final Map<String, Set<String>> ownerKeys = new LinkedHashMap<>();

    boolean containsKey(String key) {
        return commands.containsKey(key);
    }

    void add(String owner, String key, T value) {
        commands.put(key, value);
        ownerKeys.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(key);
    }

    Collection<T> removeOwner(String owner) {
        Set<String> keys = ownerKeys.remove(owner);
        if (keys == null) return List.of();

        List<T> removed = new ArrayList<>(keys.size());
        for (String key : keys) {
            T value = commands.remove(key);
            if (value != null) removed.add(value);
        }
        return removed;
    }

    Collection<T> removeAll() {
        List<T> removed = new ArrayList<>(commands.values());
        commands.clear();
        ownerKeys.clear();
        return removed;
    }

    int size() {
        return commands.size();
    }

    Set<String> keys() {
        return new LinkedHashSet<>(commands.keySet());
    }
}
