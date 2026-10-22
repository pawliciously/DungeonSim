package dev.pawfect.bloodcamp.dungeon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

final class MushroomRoom {
    static final String ROOM = "mushroom-1";
    private static final int[] BOTTOM = {20, 69, 21};
    private static final int[][] TOP = {{12, 80, 14}, {12, 80, 16}, {15, 80, 20}, {12, 80, 21}};
    private static final int[] LANDING = {14, 80, 18};
    private static final int STAY_TICKS = 100;

    private record Trip(World world, Location back, BukkitTask task) {}

    private final JavaPlugin plugin;
    private final Map<UUID, Trip> trips = new HashMap<>();

    MushroomRoom(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    boolean click(Player player, Block block, BiFunction<Integer, Integer, int[]> frame) {
        if (at(block, BOTTOM, frame)) {
            up(player, frame);
            return true;
        }
        for (int[] top : TOP) {
            if (!at(block, top, frame)) continue;
            Trip trip = trips.get(player.getUniqueId());
            if (trip != null) back(player, trip);
            return true;
        }
        return false;
    }

    private static boolean at(Block block, int[] rel, BiFunction<Integer, Integer, int[]> frame) {
        int[] w = frame.apply(rel[0], rel[2]);
        return block.getX() == w[0] && block.getY() == rel[1] && block.getZ() == w[1];
    }

    private void up(Player player, BiFunction<Integer, Integer, int[]> frame) {
        if (trips.containsKey(player.getUniqueId())) return;
        Location back = player.getLocation();
        int[] w = frame.apply(LANDING[0], LANDING[2]);
        effects(player, back);
        Net.teleportKeepLook(player, w[0] + 0.5, LANDING[1], w[1] + 0.5);
        player.setFallDistance(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, STAY_TICKS, 4, false, false));
        effects(player, player.getLocation());
        UUID id = player.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Trip trip = trips.get(id);
            Player p = Bukkit.getPlayer(id);
            if (trip != null && p != null) back(p, trip);
            else trips.remove(id);
        }, STAY_TICKS);
        trips.put(id, new Trip(player.getWorld(), back, task));
    }

    private void back(Player player, Trip trip) {
        trips.remove(player.getUniqueId());
        trip.task().cancel();
        if (player.getWorld() != trip.world()) return;
        effects(player, player.getLocation());
        Net.teleportKeepLook(player, trip.back().getX(), trip.back().getY(), trip.back().getZ());
        player.setFallDistance(0);
        player.removePotionEffect(PotionEffectType.NAUSEA);
        effects(player, trip.back());
    }

    private static void effects(Player player, Location at) {
        at.getWorld().playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        at.getWorld().spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.5);
    }
}
