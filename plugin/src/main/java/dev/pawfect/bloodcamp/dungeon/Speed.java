package dev.pawfect.bloodcamp.dungeon;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class Speed {
    static final int MIN = 100;
    static final int CAP = 400;
    static final int MAX = 500;
    static final int DEFAULT = 400;

    private static final double VANILLA = 0.1;
    private static final double PLAYER_INPUT = 0.98;
    private static final double WALK = 4.317;
    private static final double SPRINT = 5.612;

    private static NamespacedKey key;

    private Speed() {}

    static void init(JavaPlugin plugin) {
        key = new NamespacedKey(plugin, "speed");
    }

    static int get(Player player) {
        Integer v = player.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return v == null ? DEFAULT : clamp(v);
    }

    static int set(Player player, int speed) {
        int v = clamp(speed);
        player.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, v);
        return v;
    }

    static int clamp(int speed) {
        return Math.max(MIN, Math.min(MAX, speed));
    }

    static void apply(Player player, int speed) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null) attr.setBaseValue(VANILLA * speed / 100.0);
    }

    static void applyToMob(Attributable mob, int speed) {
        AttributeInstance attr = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null) attr.setBaseValue(Math.sqrt(VANILLA * speed / 100.0 * PLAYER_INPUT));
    }

    static void reset(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null) attr.setBaseValue(VANILLA);
    }

    static String walk(int speed) {
        return String.format("%.2f", WALK * speed / 100.0);
    }

    static String sprint(int speed) {
        return String.format("%.2f", SPRINT * speed / 100.0);
    }
}
