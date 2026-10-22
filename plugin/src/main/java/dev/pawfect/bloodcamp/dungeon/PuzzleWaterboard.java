package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Piston;
import org.bukkit.block.data.type.PistonHead;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;

final class PuzzleWaterboard extends Puzzle {
    enum Lever {
        COAL(20, 61, 10, Material.COAL_BLOCK), GOLD(20, 61, 15, Material.GOLD_BLOCK), QUARTZ(20, 61, 20, Material.QUARTZ_BLOCK),
        DIAMOND(10, 61, 20, Material.DIAMOND_BLOCK), EMERALD(10, 61, 15, Material.EMERALD_BLOCK), CLAY(10, 61, 10, Material.TERRACOTTA),
        WATER(15, 60, 5, null);

        final int x, y, z;
        final Material block;

        Lever(int x, int y, int z, Material block) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }
    }

    private static final int[] GATE_Z = {19, 18, 17, 16, 15};
    private static final int[] DROP_X = {24, 20, 15, 10, 6};
    private static final int DROP_Y = 58, BOARD_Z = 26, SLOT_Z = 27, PISTON_Z = 28;
    private static final int DAM_X = 15, DAM_Y = 82;
    private static final int CHEST_X = 15, CHEST_Y = 56, CHEST_Z = 22;
    private static final int RETRACT_TICKS = 2;

    private static final class Slider {
        final int x, y;
        final Material type;
        final Piston piston;
        boolean out;

        Slider(int x, int y, Material type, Piston piston, boolean out) {
            this.x = x;
            this.y = y;
            this.type = type;
            this.piston = piston;
            this.out = out;
        }
    }

    private final List<Slider> switches = new ArrayList<>();
    private final boolean[] closed = new boolean[5];
    private final boolean[] wet = new boolean[5];
    private final BlockData[] wool = new BlockData[5];
    private final Piston[][] gatePistons = new Piston[5][5];
    private Piston damPiston;
    private boolean damOpen;
    private boolean chest;

    PuzzleWaterboard(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
    }

    @Override
    void prepare() {
        switches.clear();
        for (int y = DROP_Y; y <= DAM_Y; y++) {
            for (int x = 4; x <= 26; x++) {
                if (!(block(x, y, PISTON_Z).getBlockData() instanceof Piston piston)) continue;
                Material front = block(x, y, BOARD_Z).getType(), back = block(x, y, SLOT_Z).getType();
                if (piston.isExtended() && leverOf(front) != null) switches.add(new Slider(x, y, front, retracted(piston), true));
                else if (!piston.isExtended() && leverOf(back) != null) switches.add(new Slider(x, y, back, piston, false));
            }
        }
        if (block(DAM_X, DAM_Y, PISTON_Z).getBlockData() instanceof Piston piston) damPiston = retracted(piston);
        for (int i = 0; i < 5; i++) {
            int z = GATE_Z[i];
            wool[i] = block(13, 56, z).getBlockData();
            int[][] at = pistonSpots();
            for (int p = 0; p < at.length; p++) {
                if (block(at[p][0], at[p][1], z).getBlockData() instanceof Piston piston) gatePistons[i][p] = retracted(piston);
            }
            closed[i] = false;
            wet[i] = false;
        }
        damOpen = false;
        chest = false;
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        java.util.Collections.shuffle(order, random);
        for (int i = 0; i < 3; i++) setGate(order.get(i), true, false);
    }

    @Override
    void end() {
        for (int y = DROP_Y - 2; y < DAM_Y; y++) {
            for (int x = 4; x <= 26; x++) {
                for (int z = BOARD_Z - 1; z <= SLOT_Z; z++) {
                    Block b = block(x, y, z);
                    if (b.getType() == Material.WATER) b.setType(Material.AIR, false);
                }
            }
        }
    }

    @Override
    void tick() {
        pollGates();
    }

    @Override
    boolean click(Player player, Block block, boolean right) {
        if (!right) return false;
        for (Lever lever : Lever.values()) {
            if (!is(block, lever.x, lever.y, lever.z)) continue;
            pull(lever);
            return true;
        }
        if (chest && is(block, CHEST_X, CHEST_Y, CHEST_Z)) {
            solved(player, "completed the Water Board!");
            return true;
        }
        return false;
    }

    void pull(Lever lever) {
        Block leverBlock = block(lever.x, lever.y, lever.z);
        boolean on = false;
        if (leverBlock.getBlockData() instanceof Switch data) {
            on = !data.isPowered();
            data.setPowered(on);
            set(leverBlock, data);
        }
        world.playSound(leverBlock.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_LEVER_CLICK, 0.3f, on ? 0.6f : 0.5f);
        if (lever == Lever.WATER) {
            damOpen = !damOpen;
            if (damOpen) later(RETRACT_TICKS, () -> {
                if (damOpen) moveDam(false);
            });
            else moveDam(true);
            return;
        }
        boolean moved = false;
        for (Slider s : switches) {
            if (s.type != lever.block) continue;
            moved = true;
            s.out = !s.out;
            if (s.out) moveSwitch(s, true);
            else later(RETRACT_TICKS, () -> {
                if (!s.out) moveSwitch(s, false);
            });
        }
        if (moved) world.playSound(block(15, 70, BOARD_Z).getLocation(), on ? Sound.BLOCK_PISTON_EXTEND : Sound.BLOCK_PISTON_CONTRACT, 0.5f, 1f);
    }

    private void moveSwitch(Slider s, boolean out) {
        Piston piston = (Piston) s.piston.clone();
        piston.setExtended(out);
        set(s.x, s.y, PISTON_Z, piston);
        set(s.x, s.y, SLOT_Z, out ? head(s.piston) : s.type.createBlockData());
        setUpdating(s.x, s.y, BOARD_Z, out ? s.type.createBlockData() : Material.AIR.createBlockData());
    }

    private void moveDam(boolean shut) {
        if (damPiston == null) return;
        Piston piston = (Piston) damPiston.clone();
        piston.setExtended(shut);
        set(DAM_X, DAM_Y, PISTON_Z, piston);
        set(DAM_X, DAM_Y, SLOT_Z, shut ? head(damPiston) : Material.LAPIS_BLOCK.createBlockData());
        setUpdating(DAM_X, DAM_Y, BOARD_Z, shut ? Material.LAPIS_BLOCK.createBlockData() : Material.AIR.createBlockData());
        world.playSound(block(DAM_X, DAM_Y, BOARD_Z).getLocation(), shut ? Sound.BLOCK_PISTON_EXTEND : Sound.BLOCK_PISTON_CONTRACT, 0.6f, 1f);
    }

    void pollGates() {
        if (chest) return;
        for (int i = 0; i < 5; i++) {
            boolean now = block(DROP_X[i], DROP_Y, BOARD_Z).getType() == Material.WATER;
            if (now && !wet[i]) setGate(i, !closed[i], true);
            wet[i] = now;
        }
        for (boolean c : closed) if (c) return;
        chest = true;
        set(CHEST_X, CHEST_Y, CHEST_Z, state("minecraft:chest[facing=north]"));
        world.playSound(block(CHEST_X, CHEST_Y, CHEST_Z).getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.7f, 1.4f);
    }

    private static int[][] pistonSpots() {
        return new int[][]{{12, 56, 1, 0}, {12, 57, 1, 0}, {18, 56, -1, 0}, {18, 57, -1, 0}, {15, 54, 0, 1}};
    }

    private void setGate(int i, boolean close, boolean sound) {
        closed[i] = close;
        int z = GATE_Z[i];
        int[][] spots = pistonSpots();
        for (int p = 0; p < spots.length; p++) {
            Piston base = gatePistons[i][p];
            if (base == null) continue;
            int x = spots[p][0], y = spots[p][1], dx = spots[p][2], dy = spots[p][3];
            Piston piston = (Piston) base.clone();
            piston.setExtended(close);
            set(x, y, z, piston);
            set(x + dx, y + dy, z, close ? head(base) : wool[i]);
            set(x + 2 * dx, y + 2 * dy, z, close ? wool[i] : Material.AIR.createBlockData());
        }
        set(15, 57, z, close ? wool[i] : Material.AIR.createBlockData());
        if (sound) world.playSound(block(15, 57, z).getLocation(), close ? Sound.BLOCK_PISTON_EXTEND : Sound.BLOCK_PISTON_CONTRACT, 0.6f, 1f);
    }

    void forceGates(String combo) {
        for (int i = 0; i < 5; i++) setGate(i, combo.indexOf((char) ('0' + i)) >= 0, false);
    }

    boolean chestShown() {
        return chest;
    }

    String gateState() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) if (closed[i]) sb.append(i);
        return sb.toString();
    }

    boolean waterZone(Block b) {
        if (!b.getWorld().equals(world)) return false;
        int[] r = rel(b);
        return r[0] >= 4 && r[0] <= 26 && r[1] >= DROP_Y - 2 && r[1] <= DAM_Y + 6 && r[2] >= BOARD_Z - 1 && r[2] <= SLOT_Z;
    }

    private static Lever leverOf(Material m) {
        for (Lever l : Lever.values()) if (l.block == m) return l;
        return null;
    }

    private static Piston retracted(Piston piston) {
        Piston copy = (Piston) piston.clone();
        copy.setExtended(false);
        return copy;
    }

    private static BlockData head(Piston piston) {
        PistonHead head = (PistonHead) Material.PISTON_HEAD.createBlockData();
        head.setFacing(piston.getFacing());
        head.setType(piston.getMaterial() == Material.STICKY_PISTON ? org.bukkit.block.data.type.TechnicalPiston.Type.STICKY
            : org.bukkit.block.data.type.TechnicalPiston.Type.NORMAL);
        return head;
    }

}
