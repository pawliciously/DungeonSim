package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;

final class PuzzleSilverfish extends Puzzle {
    private static final int SIZE = 17;
    private static final int FLOOR_Y = 66;
    private static final int START_ROW = 24 - 9, START_COL = 23 - 9;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final boolean[][] blocked = new boolean[SIZE][SIZE];
    private final ArrayDeque<int[]> path = new ArrayDeque<>();
    private Silverfish fish;
    private int row, col;
    private boolean leaving;

    PuzzleSilverfish(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
    }

    private static int x(int col) {
        return 23 - col;
    }

    private static int z(int row) {
        return 24 - row;
    }

    private static boolean goal(int row, int col) {
        return row == 0 && col >= 7 && col <= 9;
    }

    @Override
    void begin() {
        for (int r = 0; r < SIZE; r++) for (int c = 0; c < SIZE; c++) blocked[r][c] = !block(x(c), FLOOR_Y + 1, z(r)).isEmpty();
        int[] start = null;
        int bestScore = Integer.MAX_VALUE;
        for (int r = 0; r < SIZE; r++) for (int c = 0; c < SIZE; c++) {
            if (blocked[r][c] || goal(r, c)) continue;
            int score = Math.abs(r - START_ROW) * 2 + Math.abs(c - START_COL);
            if (score >= bestScore || distance(r, c) < 0) continue;
            bestScore = score;
            start = new int[]{r, c};
        }
        if (start == null) start = new int[]{START_ROW, START_COL};
        row = start[0];
        col = start[1];
        leaving = false;
        path.clear();
        fish = spawn(at(row, col), Silverfish.class, s -> {
            s.setAI(false);
            s.setGravity(false);
            s.setRemoveWhenFarAway(false);
        });
        Item tnt = spawn(fish.getLocation(), Item.class, i -> {
            i.setItemStack(new ItemStack(Material.TNT));
            i.setCanPlayerPickup(false);
            i.setCanMobPickup(false);
            i.setUnlimitedLifetime(true);
            i.setGravity(false);
        });
        fish.addPassenger(tnt);
    }

    private Location at(double row, double col) {
        return loc(x(0) - col + 0.5, FLOOR_Y + 1, z(0) - row + 0.5, 0f);
    }

    private int distance(int fromRow, int fromCol) {
        int[][] seen = new int[SIZE][SIZE];
        for (int[] s : seen) java.util.Arrays.fill(s, -1);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen[fromRow][fromCol] = 0;
        queue.add(new int[]{fromRow, fromCol});
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            if (goal(at[0], at[1])) return seen[at[0]][at[1]];
            for (int[] d : DIRS) {
                int[] to = slide(at[0], at[1], d[0], d[1]);
                if (seen[to[0]][to[1]] >= 0) continue;
                seen[to[0]][to[1]] = seen[at[0]][at[1]] + 1;
                queue.add(to);
            }
        }
        return -1;
    }

    private int[] slide(int r, int c, int dr, int dc) {
        while (r + dr >= 0 && r + dr < SIZE && c + dc >= 0 && c + dc < SIZE && !blocked[r + dr][c + dc]) {
            r += dr;
            c += dc;
        }
        return new int[]{r, c};
    }

    @Override
    boolean hit(Player player, Entity entity, boolean melee) {
        if (entity != fish) return false;
        if (!path.isEmpty() || leaving) return true;
        Location f = fish.getLocation();
        double yaw = Math.toRadians(player.getLocation().getYaw());
        double[] d = frame.rotate(-Math.sin(yaw), Math.cos(yaw), -frame.turns());
        int dr = 0, dc = 0;
        if (Math.abs(d[0]) > Math.abs(d[1])) dc = d[0] > 0 ? -1 : 1;
        else dr = d[1] > 0 ? -1 : 1;
        int r = row, c = col;
        while (r + dr >= 0 && r + dr < SIZE && c + dc >= 0 && c + dc < SIZE && !blocked[r + dr][c + dc]) {
            r += dr;
            c += dc;
            path.add(new int[]{r, c});
        }
        world.playSound(f, Sound.ENTITY_SILVERFISH_HURT, 0.7f, 1f);
        if (path.isEmpty()) return true;
        int[] end = path.getLast();
        if (goal(end[0], end[1])) {
            leaving = true;
            path.add(new int[]{-1, end[1]});
            path.add(new int[]{-2, end[1]});
            later(path.size() + 2, () -> solved(player, "got the Silverfish out!"));
        } else if (distance(end[0], end[1]) < 0) {
            later(path.size() + 10, () -> {
                say("§cThe Silverfish is stuck, resetting.");
                reset();
            });
        }
        return true;
    }

    @Override
    void tick() {
        if (path.isEmpty() || fish == null) return;
        int[] next = path.poll();
        row = Math.max(0, next[0]);
        col = next[1];
        Location to = at(next[0], next[1]);
        to.setYaw(fish.getLocation().getYaw());
        fish.teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        if (next[0] < 0 && path.isEmpty()) {
            for (Entity rider : fish.getPassengers()) forget(rider);
            forget(fish);
        }
    }
}
