package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

final class PuzzleWeirdos extends Puzzle {
    private static final int[][] NPCS = {{13, 24}, {15, 25}, {17, 24}};
    private static final int Y = 69;

    private static final List<String> NAMES = List.of(
        "Ardis", "Baxter", "Benson", "Carver", "Cliffe", "Dax", "Elmo", "Eveleth", "Frank", "Gaston", "Hope", "Hugo",
        "Jamie", "Lino", "Lucky", "Luverne", "Madelyn", "Marston", "Melvin", "Montgomery", "Morris", "Ralph", "Rose",
        "Tyrone", "Victoria", "Virginia", "Weston", "Wilfred", "Winona");

    private static final List<String> RIGHT = List.of(
        "The reward is not in my chest!",
        "The reward isn't in any of our chests.",
        "My chest doesn't have the reward. We are all telling the truth.",
        "My chest has the reward and I'm telling the truth!",
        "At least one of them is lying, and the reward is not in %s's chest!",
        "Both of them are telling the truth. Also, %s has the reward in their chest!");

    private static final List<String> WRONG = List.of(
        "The reward is in my chest!",
        "One of us is telling the truth!",
        "They are both telling the truth. The reward isn't in %s's chest.",
        "We are all telling the truth!",
        "At least one of them is lying, and the reward is in %s's chest!",
        "The reward is not in my chest. They are both lying.",
        "My chest doesn't have the reward. At least one of the others is telling the truth!");

    private final String[] names = new String[3];
    private final String[] lines = new String[3];
    private final Mannequin[] npcs = new Mannequin[3];
    private final ArmorStand[] stands = new ArmorStand[3];
    private int reward;

    PuzzleWeirdos(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
    }

    @Override
    void begin() {
        List<String> pool = new ArrayList<>(NAMES);
        Collections.shuffle(pool, random);
        reward = random.nextInt(3);
        for (int i = 0; i < 3; i++) names[i] = pool.get(i);
        for (int i = 0; i < 3; i++) {
            String other = names[(i + 1 + random.nextInt(2)) % 3];
            String line = (i == reward ? RIGHT : WRONG).get(random.nextInt((i == reward ? RIGHT : WRONG).size()));
            lines[i] = line.contains("%s") ? line.formatted(other) : line;
        }
        for (int i = 0; i < 3; i++) {
            int index = i;
            npcs[i] = spawn(loc(NPCS[i][0] + 0.5, Y, NPCS[i][1] + 0.5, 180f), Mannequin.class, m -> {
                m.setAI(false);
                m.setGravity(false);
                m.setImmovable(true);
                m.setDescription(null);
                m.setCollidable(false);
                m.customName(Component.text(names[index], NamedTextColor.YELLOW));
                m.setCustomNameVisible(true);
            });
            org.bukkit.block.Block at = block(NPCS[i][0], Y, NPCS[i][1]);
            stands[i] = spawn(new org.bukkit.Location(world, at.getX() + 1, Y, at.getZ() + 1), ArmorStand.class, s -> {
                s.setInvisible(true);
                s.setSmall(true);
                s.setGravity(false);
                s.customName(Component.text(names[index]));
                s.setCustomNameVisible(false);
            });
            set(NPCS[i][0] + 1, Y, NPCS[i][1], state("minecraft:chest[facing=north]"));
        }
    }

    @Override
    boolean interact(Player player, Entity entity) {
        for (int i = 0; i < 3; i++) {
            if (npcs[i] != entity && stands[i] != entity) continue;
            player.sendMessage(Component.text("[NPC] ", NamedTextColor.YELLOW).append(Component.text(names[i] + ": ", NamedTextColor.WHITE))
                .append(Component.text(lines[i], NamedTextColor.WHITE)));
            player.playSound(entity.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 1f);
            return true;
        }
        return false;
    }

    @Override
    boolean click(Player player, Block block, boolean right) {
        if (!right || block.getType() != Material.CHEST) return false;
        for (int i = 0; i < 3; i++) {
            if (!is(block, NPCS[i][0] + 1, Y, NPCS[i][1])) continue;
            if (i == reward) {
                for (int j = 0; j < 3; j++) if (j != i) set(NPCS[j][0] + 1, Y, NPCS[j][1], Material.AIR);
                solved(player, "wasn't fooled by §r§c" + names[reward] + "§r§b!");
            } else {
                failed(player, "was fooled by §r§c" + names[i] + "§r§b!");
            }
            return true;
        }
        return false;
    }
}
