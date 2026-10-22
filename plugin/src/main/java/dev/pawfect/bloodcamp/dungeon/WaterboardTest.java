package dev.pawfect.bloodcamp.dungeon;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class WaterboardTest {
    private static final String[] COMBOS = {"012", "013", "014", "023", "024", "034", "123", "124", "134", "234"};
    private static final int SPACING = 40;
    private static final int TIMEOUT_TICKS = 30 * 20;

    private record Click(int tick, PuzzleWaterboard.Lever lever) {}

    private static final class Board {
        final int variant;
        final String combo;
        final PuzzleWaterboard puzzle;
        final List<Click> clicks;
        int next;
        int solvedAt = -1;

        Board(int variant, String combo, PuzzleWaterboard puzzle, List<Click> clicks) {
            this.variant = variant;
            this.combo = combo;
            this.puzzle = puzzle;
            this.clicks = clicks;
        }
    }

    private WaterboardTest() {}

    static void run(JavaPlugin plugin, DungeonManager manager, RoomLibrary library, Puzzles puzzles, CommandSender sender) {
        JsonObject key;
        Path file = plugin.getDataFolder().toPath().resolve("debug/watertimes.json");
        try (Reader reader = Files.newBufferedReader(file)) {
            key = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            sender.sendMessage("No answer key at " + file + ": " + e.getMessage());
            return;
        }
        Map<Integer, RoomData> layouts = new TreeMap<>();
        for (RoomLibrary.Entry e : library.pool("puzzle")) {
            String f = e.file().getFileName().toString();
            if (!e.id().equals("water-puzzle") || !f.matches("layout-\\d\\.json")) continue;
            try {
                layouts.put(f.charAt(7) - '0', library.load(e));
            } catch (Exception ex) {
                sender.sendMessage("Couldn't load " + f + ": " + ex.getMessage());
                return;
            }
        }
        if (layouts.isEmpty()) {
            sender.sendMessage("No Water Board layouts on the server.");
            return;
        }
        World world = manager.createWorld("ds_wbtest_" + System.currentTimeMillis() % 100000);
        for (int cx = -2; cx <= (layouts.size() * SPACING) >> 4; cx++) {
            for (int cz = -2; cz <= (COMBOS.length * SPACING) >> 4; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        }
        List<int[]> jobs = new ArrayList<>();
        for (int v : layouts.keySet()) for (int c = 0; c < COMBOS.length; c++) jobs.add(new int[]{v, c});
        List<Board> boards = new ArrayList<>();
        int[] index = {0};
        sender.sendMessage("Building " + jobs.size() + " Water Boards...");
        BukkitTask[] paste = new BukkitTask[1];
        paste[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (index[0] >= jobs.size()) {
                paste[0].cancel();
                play(plugin, manager, puzzles, world, boards, sender);
                return;
            }
            int[] job = jobs.get(index[0]++);
            int v = job[0], c = job[1];
            RoomData data = layouts.get(v);
            int cornerX = (v - 1) * SPACING, cornerZ = c * SPACING;
            for (int y = 0; y < data.sy; y++) {
                for (int z = 0; z < data.sz; z++) {
                    for (int x = 0; x < data.sx; x++) {
                        int i = data.data[(y * data.sz + z) * data.sx + x];
                        if (data.isAir(i)) continue;
                        world.getBlockAt(cornerX + data.ox + x, data.oy + y, cornerZ + data.oz + z).setBlockData(data.state(i, 0), false);
                    }
                }
            }
            Puzzle p = puzzles.add(world, new Puzzle.Frame(0, cornerX, cornerZ), "water-puzzle", "Water Board", -1, null);
            if (!(p instanceof PuzzleWaterboard wb)) return;
            JsonObject times = key.getAsJsonObject(String.valueOf(v)).getAsJsonObject(COMBOS[c]);
            boards.add(new Board(v, COMBOS[c], wb, clicks(times)));
        }, 1, 1);
    }

    private static List<Click> clicks(JsonObject times) {
        List<Click> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : times.entrySet()) {
            PuzzleWaterboard.Lever lever = switch (e.getKey()) {
                case "terracotta", "hardened_clay" -> PuzzleWaterboard.Lever.CLAY;
                case "coal", "coal_block" -> PuzzleWaterboard.Lever.COAL;
                case "gold", "gold_block" -> PuzzleWaterboard.Lever.GOLD;
                case "quartz", "quartz_block" -> PuzzleWaterboard.Lever.QUARTZ;
                case "diamond", "diamond_block" -> PuzzleWaterboard.Lever.DIAMOND;
                case "emerald", "emerald_block" -> PuzzleWaterboard.Lever.EMERALD;
                case "water" -> PuzzleWaterboard.Lever.WATER;
                default -> null;
            };
            if (lever == null) continue;
            for (JsonElement t : e.getValue().getAsJsonArray()) out.add(new Click((int) Math.round(t.getAsDouble() * 20), lever));
        }
        out.sort((a, b) -> a.tick() != b.tick() ? Integer.compare(a.tick(), b.tick())
            : Boolean.compare(a.lever() == PuzzleWaterboard.Lever.WATER, b.lever() == PuzzleWaterboard.Lever.WATER));
        return out;
    }

    private static void play(JavaPlugin plugin, DungeonManager manager, Puzzles puzzles, World world, List<Board> boards, CommandSender sender) {
        for (Board b : boards) {
            b.puzzle.start();
            b.puzzle.forceGates(b.combo);
        }
        sender.sendMessage("Playing " + boards.size() + " answer keys, about 30s...");
        int[] tick = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int t = tick[0]++;
            boolean running = false;
            for (Board b : boards) {
                while (b.next < b.clicks.size() && b.clicks.get(b.next).tick() <= t) b.puzzle.pull(b.clicks.get(b.next++).lever());
                b.puzzle.pollGates();
                if (b.solvedAt < 0 && b.puzzle.chestShown()) b.solvedAt = t;
                if (b.solvedAt < 0) running = true;
            }
            if (running && t < TIMEOUT_TICKS) return;
            task[0].cancel();
            int solved = 0;
            List<String> lines = new ArrayList<>();
            for (Board b : boards) {
                if (b.solvedAt >= 0) solved++;
                lines.add("v" + b.variant + " " + b.combo + ": " + (b.solvedAt >= 0
                    ? "solved " + String.format("%.2f", b.solvedAt / 20.0) + "s"
                    : "NOT solved, still closed: " + b.puzzle.gateState()));
            }
            sender.sendMessage("Water Board test: " + solved + "/" + boards.size() + " solved.");
            for (String line : lines) {
                plugin.getLogger().info("[wbtest] " + line);
                if (!line.contains("solved ")) sender.sendMessage(line);
            }
            puzzles.remove(world);
            manager.unloadAndDelete(world);
        }, 1, 1);
    }
}
