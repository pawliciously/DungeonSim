package dev.pawfect.bloodcamp.dungeon;

import dev.pawfect.bloodcamp.gen.LayoutGenerator;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

final class Times {
    record Clear(UUID player, String name, String seed, String floor, long millis, int speed, int party, long at) {}

    private static final String SEASON = "season-" + LayoutGenerator.VERSION;

    private final File file;
    private final Logger log;
    private final YamlConfiguration yaml;
    private final List<Clear> clears = new ArrayList<>();

    Times(File file, Logger log) {
        this.file = file;
        this.log = log;
        this.yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList(SEASON)) {
            try {
                clears.add(new Clear(UUID.fromString(String.valueOf(m.get("uuid"))), String.valueOf(m.get("name")),
                    String.valueOf(m.get("seed")), String.valueOf(m.get("floor")), ((Number) m.get("ms")).longValue(),
                    ((Number) m.get("speed")).intValue(), ((Number) m.get("party")).intValue(), ((Number) m.get("at")).longValue()));
            } catch (RuntimeException e) {
                log.warning("Skipped a bad clear in " + file.getName() + ": " + m);
            }
        }
    }

    synchronized void add(Clear c) {
        clears.add(c);
        List<Map<String, Object>> out = new ArrayList<>(clears.size());
        for (Clear x : clears) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", x.player().toString());
            m.put("name", x.name());
            m.put("seed", x.seed());
            m.put("floor", x.floor());
            m.put("ms", x.millis());
            m.put("speed", x.speed());
            m.put("party", x.party());
            m.put("at", x.at());
            out.add(m);
        }
        yaml.set(SEASON, out);
        try {
            yaml.save(file);
        } catch (IOException e) {
            log.warning("Couldn't save clears: " + e.getMessage());
        }
    }

    synchronized Clear best(UUID player, Predicate<Clear> which) {
        Clear best = null;
        for (Clear c : clears) {
            if (c.player().equals(player) && which.test(c) && (best == null || c.millis() < best.millis())) best = c;
        }
        return best;
    }

    synchronized List<Clear> of(UUID player) {
        return clears.stream().filter(c -> c.player().equals(player)).toList();
    }

    synchronized List<Clear> top(Predicate<Clear> which, int limit) {
        Map<UUID, Clear> perPlayer = new HashMap<>();
        for (Clear c : clears) {
            if (!which.test(c)) continue;
            perPlayer.merge(c.player(), c, (a, b) -> b.millis() < a.millis() ? b : a);
        }
        List<Clear> out = new ArrayList<>(perPlayer.values());
        out.sort(Comparator.comparingLong(Clear::millis).thenComparingLong(Clear::at));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    static Predicate<Clear> onSeed(String seed, String floor) {
        return c -> c.seed().equals(seed) && c.floor().equals(floor);
    }

    static Predicate<Clear> onFloor(String floor) {
        return c -> c.floor().equals(floor);
    }

    static String format(long millis) {
        long minutes = millis / 60000;
        double seconds = (millis % 60000) / 1000.0;
        return minutes > 0 ? String.format("%d:%06.3f", minutes, seconds) : String.format("%.3fs", seconds);
    }
}
