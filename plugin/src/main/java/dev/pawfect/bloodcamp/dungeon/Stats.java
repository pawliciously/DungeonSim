package dev.pawfect.bloodcamp.dungeon;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

final class Stats {
    record Fastest(String name, long millis) {}
    static final class Entry {
        final Map<String, Long> counts = new HashMap<>();
        final Map<String, Fastest> fastest = new LinkedHashMap<>();
        long get(String key) {
            return counts.getOrDefault(key, 0L);
        }
        boolean has(String key) {
            return counts.containsKey(key);
        }
    }
    private final File file;
    private final Logger log;
    private final Function<UUID, List<Times.Clear>> history;
    private final Map<UUID, Entry> players = new HashMap<>();
    private boolean dirty;
    Stats(File file, Logger log, Function<UUID, List<Times.Clear>> history) {
        this.file = file;
        this.log = log;
        this.history = history;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            UUID uuid;
            try {
                uuid = UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                log.warning("Skipped bad stats entry " + id);
                continue;
            }
            Entry entry = new Entry();
            for (String key : s.getKeys(false)) if (!key.equals("fastest")) entry.counts.put(key, s.getLong(key));
            ConfigurationSection f = s.getConfigurationSection("fastest");
            if (f != null) {
                for (String key : f.getKeys(false)) entry.fastest.put(key, new Fastest(f.getString(key + ".name", key), f.getLong(key + ".ms")));
            }
            players.put(uuid, entry);
        }
    }
    Entry of(UUID id) {
        return players.computeIfAbsent(id, this::fresh);
    }
    private Entry fresh(UUID id) {
        Entry entry = new Entry();
        List<Times.Clear> clears = history.apply(id);
        if (!clears.isEmpty()) {
            entry.counts.put("clears", (long) clears.size());
            entry.counts.put("clearms", clears.stream().mapToLong(Times.Clear::millis).sum());
            entry.counts.put("pb", clears.stream().mapToLong(Times.Clear::millis).min().getAsLong());
            dirty = true;
        }
        return entry;
    }
    void add(Player p, String key) {
        add(p, key, 1);
    }
    void add(Player p, String key, long n) {
        of(p.getUniqueId()).counts.merge(key, n, Long::sum);
        dirty = true;
    }
    void max(Player p, String key, long value) {
        Entry e = of(p.getUniqueId());
        if (e.has(key) && e.get(key) >= value) return;
        e.counts.put(key, value);
        dirty = true;
    }
    void min(Player p, String key, long value) {
        Entry e = of(p.getUniqueId());
        if (e.has(key) && e.get(key) <= value) return;
        e.counts.put(key, value);
        dirty = true;
    }
    void fastest(Player p, String raw, long millis) {
        String name = raw.replace("*", "").trim();
        String key = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (key.isEmpty()) return;
        Entry e = of(p.getUniqueId());
        Fastest old = e.fastest.get(key);
        if (old != null && old.millis() <= millis) return;
        e.fastest.put(key, new Fastest(name, millis));
        dirty = true;
    }
    void save() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration yaml = new YamlConfiguration();
        players.forEach((id, entry) -> {
            String base = "players." + id;
            entry.counts.forEach((k, v) -> yaml.set(base + "." + k, v));
            entry.fastest.forEach((k, f) -> {
                yaml.set(base + ".fastest." + k + ".name", f.name());
                yaml.set(base + ".fastest." + k + ".ms", f.millis());
            });
        });
        try {
            yaml.save(file);
        } catch (IOException e) {
            log.warning("Couldn't save stats: " + e.getMessage());
        }
    }
}
