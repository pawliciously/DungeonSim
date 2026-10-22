package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

final class PuzzleTeleport extends Puzzle {
    private static final int Y = 69;
    private static final int[] START = {15, 12}, END = {15, 14};
    private static final int[] CHEST = {15, 70, 20};
    private static final double PADTOP = Y + 0.8125;
    private static final float EXITYAW = 180f;
    private static final int[][] PADS = {
        {4, 6}, {10, 6}, {20, 6}, {26, 6}, {4, 12}, {10, 12}, {20, 12}, {26, 12},
        {4, 14}, {10, 14}, {20, 14}, {26, 14}, {4, 20}, {10, 20}, {20, 20}, {26, 20},
        {4, 22}, {10, 22}, {12, 22}, {18, 22}, {20, 22}, {26, 22},
        {4, 28}, {10, 28}, {12, 28}, {18, 28}, {20, 28}, {26, 28}};
    private static final int[][] CENTERS = {{7, 9}, {23, 9}, {7, 17}, {23, 17}, {7, 25}, {15, 25}, {23, 25}};

    private final Map<Integer, int[]> links = new HashMap<>();
    private int[] finalPad = PADS[0];
    private final Map<UUID, Integer> standing = new HashMap<>();

    PuzzleTeleport(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
    }

    private static int cell(int[] pad) {
        if (pad == START) return -1;
        for (int i = 0; i < CENTERS.length; i++) {
            if (Math.abs(pad[0] - CENTERS[i][0]) <= 3 && Math.abs(pad[1] - CENTERS[i][1]) <= 3) return i;
        }
        return -2;
    }

    private static int key(int[] pad) {
        return pad[0] * 100 + pad[1];
    }

    @Override
    void begin() {
        links.clear();
        int finalIndex = random.nextInt(PADS.length);
        finalPad = PADS[finalIndex];
        links.put(key(finalPad), END);
        links.put(key(END), START);
        List<int[]> rest = new ArrayList<>();
        rest.add(START);
        for (int i = 0; i < PADS.length; i++) if (i != finalIndex) rest.add(PADS[i]);
        for (int attempt = 0; attempt < 500; attempt++) {
            Collections.shuffle(rest, random);
            boolean ok = true;
            for (int i = 0; i < rest.size(); i += 2) if (cell(rest.get(i)) == cell(rest.get(i + 1))) ok = false;
            if (ok) break;
        }
        for (int i = 0; i < rest.size(); i += 2) {
            links.put(key(rest.get(i)), rest.get(i + 1));
            links.put(key(rest.get(i + 1)), rest.get(i));
        }
    }

    @Override
    void end() {
        standing.clear();
    }

    @Override
    boolean ticksWhenSolved() {
        return true;
    }

    @Override
    void tick() {
        for (Player p : playersInside()) {
            double[] r = rel(p.getLocation());
            int rx = (int) Math.floor(r[0]), rz = (int) Math.floor(r[2]);
            boolean onPad = (int) Math.floor(r[1]) == Y;
            Integer here = onPad ? rx * 100 + rz : null;
            Integer was = standing.get(p.getUniqueId());
            if (here == null || !links.containsKey(here)) {
                standing.remove(p.getUniqueId());
                continue;
            }
            if (here.equals(was)) continue;
            int[] to = links.get(here);
            standing.put(p.getUniqueId(), key(to));
            Location dest = loc(to[0] + 0.5, Y + 0.5, to[1] + 0.5, EXITYAW);
            if (to != START) {
                Location aim = loc(finalPad[0] + 0.5, Y, finalPad[1] + 0.5, 0f);
                dest.setYaw((float) Math.toDegrees(Math.atan2(-(aim.getX() - dest.getX()), aim.getZ() - dest.getZ())));
            }
            dest.setPitch(0f);
            p.teleport(dest);
            p.setFallDistance(0);
            world.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1f);
            after(1, () -> {
                Location now = p.getLocation();
                if (!p.isOnline() || now.getWorld() != world || now.getY() >= PADTOP || Math.abs(now.getX() - dest.getX()) > 0.6 || Math.abs(now.getZ() - dest.getZ()) > 0.6) return;
                Net.teleportKeepLook(p, now.getX(), PADTOP, now.getZ());
                p.setFallDistance(0);
            });
            if (to == END && state == State.ACTIVE) {
                set(CHEST[0], CHEST[1], CHEST[2], state("minecraft:chest[facing=north]"));
                solved(p, "found the way through the Teleport Maze!");
            }
        }
    }
    boolean noteleport(Location l) {
        return inside(l);
    }
}
