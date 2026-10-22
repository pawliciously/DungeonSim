package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class PuzzleCreeper extends Puzzle {
    private static final int BASE_X = 15, BASE_Y = 74, BASE_Z = 15;
    private static final int BEAMS = 4;
    private static final long VOLLEY_MS = 350;
    private static final Particle.DustOptions SELECTED = new Particle.DustOptions(Color.fromRGB(0xF2C14E), 1.2f);
    private static final Particle.DustOptions BEAM = new Particle.DustOptions(Color.fromRGB(0x7BD8C8), 0.9f);

    private final List<int[]> lanterns = new ArrayList<>();
    private final List<int[][]> made = new ArrayList<>();
    private final Map<UUID, Long> lastHit = new HashMap<>();
    private int[] selected;
    private Creeper creeper;

    PuzzleCreeper(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
    }

    @Override
    void begin() {
        lanterns.clear();
        made.clear();
        lastHit.clear();
        selected = null;
        for (int y = 69; y <= 100; y++) for (int x = 0; x <= 30; x++) for (int z = 0; z <= 30; z++) {
            if (x == BASE_X && y == BASE_Y && z == BASE_Z) continue;
            Material m = block(x, y, z).getType();
            if (m != Material.SEA_LANTERN && m != Material.PRISMARINE) continue;
            if (m == Material.PRISMARINE) set(x, y, z, Material.SEA_LANTERN);
            lanterns.add(new int[]{x, y, z});
        }
        creeper = spawn(loc(BASE_X + 0.5, BASE_Y + 1, BASE_Z + 0.5, 0f), Creeper.class, c -> {
            c.setAI(false);
            c.setGravity(false);
            c.setRemoveWhenFarAway(false);
        });
    }

    @Override
    void end() {
        lastHit.clear();
    }

    private static boolean throughCreeper(int[] a, int[] b) {
        double[] lo = {BASE_X, BASE_Y + 1, BASE_Z}, hi = {BASE_X + 1, BASE_Y + 4, BASE_Z + 1};
        double t0 = 0, t1 = 1;
        for (int i = 0; i < 3; i++) {
            double p = a[i] + 0.5, d = b[i] - a[i];
            if (d == 0) {
                if (p < lo[i] || p > hi[i]) return false;
                continue;
            }
            double ta = (lo[i] - p) / d, tb = (hi[i] - p) / d;
            t0 = Math.max(t0, Math.min(ta, tb));
            t1 = Math.min(t1, Math.max(ta, tb));
            if (t0 > t1) return false;
        }
        return true;
    }

    private int[] lantern(Block block) {
        if (block.getType() != Material.SEA_LANTERN) return null;
        int[] r = rel(block);
        for (int[] l : lanterns) if (l[0] == r[0] && l[1] == r[1] && l[2] == r[2]) return l;
        return null;
    }

    @Override
    boolean click(Player player, Block block, boolean right) {
        int[] l = lantern(block);
        if (l == null) return false;
        pick(player, l);
        return true;
    }

    @Override
    boolean arrow(Player player, Block block) {
        int[] l = lantern(block);
        if (l == null) return false;
        pick(player, l);
        return true;
    }

    private void pick(Player player, int[] l) {
        long now = System.currentTimeMillis();
        Long last = lastHit.get(player.getUniqueId());
        if (last != null && now - last < VOLLEY_MS) return;
        lastHit.put(player.getUniqueId(), now);
        if (selected == l) return;
        if (selected == null || !throughCreeper(selected, l)) {
            if (selected != null) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            selected = l;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f);
            return;
        }
        int[] a = selected;
        selected = null;
        set(a[0], a[1], a[2], Material.PRISMARINE);
        set(l[0], l[1], l[2], Material.PRISMARINE);
        lanterns.remove(a);
        lanterns.remove(l);
        made.add(new int[][]{a, l});
        world.playSound(creeper.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 0.7f, 1.2f);
        if (made.size() >= BEAMS) {
            set(BASE_X, BASE_Y, BASE_Z, Material.STONE_BRICKS);
            if (block(15, 69, 15).isEmpty()) set(15, 69, 15, state("minecraft:chest[facing=south]"));
            forget(creeper);
            solved(player, "connected all the Creeper Beams!");
        }
    }

    @Override
    void tick() {
        if (world.getFullTime() % 4 != 0) return;
        if (selected != null) {
            Location at = loc(selected[0] + 0.5, selected[1] + 0.5, selected[2] + 0.5, 0f);
            world.spawnParticle(Particle.DUST, at, 8, 0.4, 0.4, 0.4, 0, SELECTED);
        }
        for (int[][] beam : made) {
            Location a = loc(beam[0][0] + 0.5, beam[0][1] + 0.5, beam[0][2] + 0.5, 0f);
            Location b = loc(beam[1][0] + 0.5, beam[1][1] + 0.5, beam[1][2] + 0.5, 0f);
            Vector step = b.toVector().subtract(a.toVector());
            int n = (int) (step.length() * 2);
            step.multiply(1.0 / n);
            Location p = a.clone();
            for (int i = 0; i <= n; i++, p.add(step)) world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, BEAM);
        }
    }
}
