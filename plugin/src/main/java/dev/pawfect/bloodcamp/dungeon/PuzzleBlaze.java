package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

final class PuzzleBlaze extends Puzzle {
    private static final int COUNT = 10;
    private static final double MAX_RADIUS = 4.5;

    private record Target(Blaze blaze, ArmorStand tag, int health) {}

    private final boolean highestFirst;
    private final List<Target> order = new ArrayList<>();

    PuzzleBlaze(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
        this.highestFirst = !room.endsWith("-low");
    }

    @Override
    void begin() {
        int minY = highestFirst ? 21 : 71, maxY = highestFirst ? 63 : 113;
        List<int[]> spots = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            int band = minY + (maxY - minY) * i / (COUNT - 1);
            for (int tries = 0; tries < 200; tries++) {
                double angle = random.nextDouble() * Math.PI * 2, radius = random.nextDouble() * MAX_RADIUS;
                int x = (int) Math.floor(15.5 + Math.cos(angle) * radius), z = (int) Math.floor(15.5 + Math.sin(angle) * radius);
                int y = Math.max(minY, Math.min(maxY, band + random.nextInt(3) - 1));
                if (!open(x, y, z)) continue;
                spots.add(new int[]{x, y, z});
                break;
            }
        }
        Set<Integer> used = new HashSet<>();
        for (int[] s : spots) {
            int health;
            do health = 100 + random.nextInt(29) * 100 + random.nextInt(100); while (!used.add(health));
            int hp = health;
            Location at = loc(s[0] + 0.5, s[1], s[2] + 0.5, random.nextFloat() * 360f);
            Blaze blaze = spawn(at, Blaze.class, b -> {
                b.setAI(false);
                b.setGravity(false);
                b.setRemoveWhenFarAway(false);
            });
            ArmorStand tag = spawn(at.clone().add(0, 2.0, 0), ArmorStand.class, a -> {
                a.setInvisible(true);
                a.setMarker(true);
                a.setGravity(false);
                a.customName(label(hp));
                a.setCustomNameVisible(true);
            });
            order.add(new Target(blaze, tag, hp));
        }
        order.sort((a, b) -> highestFirst ? b.health() - a.health() : a.health() - b.health());
    }

    private boolean open(int x, int y, int z) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (!block(x + dx, y + dy, z + dz).isEmpty()) return false;
            }
        }
        return true;
    }

    private static Component label(int health) {
        String hp = String.format("%,d", health);
        return Component.text("[", NamedTextColor.DARK_GRAY).append(Component.text("Lv15", NamedTextColor.GRAY))
            .append(Component.text("]  ", NamedTextColor.DARK_GRAY)).append(Component.text("Blaze ", NamedTextColor.RED))
            .append(Component.text(hp, NamedTextColor.GREEN)).append(Component.text("/", NamedTextColor.WHITE))
            .append(Component.text(hp, NamedTextColor.GREEN)).append(Component.text("❤", NamedTextColor.RED));
    }

    @Override
    void end() {
        order.clear();
    }

    @Override
    boolean hit(Player player, Entity entity, boolean melee) {
        if (!(entity instanceof Blaze) || order.isEmpty()) return false;
        Target next = order.getFirst();
        if (next.blaze() != entity) {
            failed(player, "killed a Blaze in the wrong order!");
            return true;
        }
        order.removeFirst();
        world.spawnParticle(Particle.LARGE_SMOKE, entity.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        world.playSound(entity.getLocation(), Sound.ENTITY_BLAZE_DEATH, 0.8f, 1f);
        forget(next.blaze());
        forget(next.tag());
        if (order.isEmpty()) solved(player, "killed the Blazes in the right order!");
        return true;
    }
}
