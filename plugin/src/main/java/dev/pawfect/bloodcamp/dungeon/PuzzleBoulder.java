package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

final class PuzzleBoulder extends Puzzle {
    private static final int COLS = 7, ROWS = 6;
    private static final int Y0 = 64;
    private static final int CHEST_X = 15, CHEST_Y = 66, CHEST_Z = 29;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final String[] LAYOUTS = {
        "010000010111101001010011100000101110000111", "100000111101111011101110001110111010000000",
        "110001110111011010001100111111100011000001", "100100101000100010100010101000101000100010",
        "000000011111101001010011100000101110000110", "000000001111100100010010001011111110000000",
        "000000010101110101010011010000010100000000", "100101001000000010101001111101010101010101"};

    private final boolean[][] box = new boolean[ROWS][COLS];
    private final boolean[][] jungle = new boolean[ROWS][COLS];

    PuzzleBoulder(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
    }

    private static int cx(int col) {
        return 24 - 3 * col;
    }

    private static int cz(int row) {
        return 24 - 3 * row;
    }

    @Override
    void prepare() {
        String layout = LAYOUTS[random.nextInt(LAYOUTS.length)];
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) box[r][c] = layout.charAt(r * COLS + c) == '1';
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) jungle[r][c] = random.nextBoolean();
        draw();
        set(CHEST_X, CHEST_Y, CHEST_Z, state("minecraft:chest[facing=north]"));
    }

    private long mask() {
        long m = 0;
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) if (box[r][c]) m |= 1L << (r * COLS + c);
        return m;
    }

    private static boolean boxed(long mask, int r, int c) {
        return r < ROWS && (mask >> (r * COLS + c) & 1) != 0;
    }

    private static long reach(long mask, int fromR, int fromC) {
        long seen = 1L << (fromR * COLS + fromC);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{fromR, fromC});
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            for (int[] d : DIRS) {
                int r = at[0] + d[0], c = at[1] + d[1];
                if (r < 0 || r > ROWS || c < 0 || c >= COLS || boxed(mask, r, c)) continue;
                long bit = 1L << (r * COLS + c);
                if ((seen & bit) != 0) continue;
                seen |= bit;
                queue.add(new int[]{r, c});
            }
        }
        return seen;
    }

    private static int solve(long start) {
        record Node(long mask, int r, int c, int depth) {}
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(start, ROWS, COLS / 2, 0));
        while (!queue.isEmpty() && seen.size() < 60_000) {
            Node n = queue.poll();
            long area = reach(n.mask(), n.r(), n.c());
            if ((area & ((1L << COLS) - 1)) != 0) return n.depth();
            if (!seen.add(n.mask() * 64 + Long.numberOfTrailingZeros(area))) continue;
            for (int cell = 0; cell <= ROWS * COLS + COLS - 1; cell++) {
                if ((area >> cell & 1) == 0) continue;
                int pr = cell / COLS, pc = cell % COLS;
                for (int[] d : DIRS) {
                    int br = pr + d[0], bc = pc + d[1], tr = br + d[0], tc = bc + d[1];
                    if (br < 0 || br >= ROWS || bc < 0 || bc >= COLS || !boxed(n.mask(), br, bc)) continue;
                    if (tr < 0 || tr >= ROWS || tc < 0 || tc >= COLS || boxed(n.mask(), tr, tc)) continue;
                    long moved = n.mask() & ~(1L << (br * COLS + bc)) | 1L << (tr * COLS + tc);
                    queue.add(new Node(moved, pr, pc, n.depth() + 1));
                }
            }
        }
        return -1;
    }

    private boolean free(int r, int c) {
        return r >= 0 && r < ROWS && c >= 0 && c < COLS && !box[r][c];
    }

    private void draw() {
        for (int x = 4; x <= 26; x++) for (int z = 7; z <= 26; z++) for (int y = Y0; y <= Y0 + 2; y++) {
            Block b = block(x, y, z);
            Material m = b.getType();
            if (m == Material.BIRCH_PLANKS || m == Material.JUNGLE_PLANKS || m == Material.STONE_BUTTON || Tag.WALL_SIGNS.isTagged(m)) {
                set(b, Material.AIR.createBlockData());
            }
        }
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            if (!box[r][c]) continue;
            Material wood = jungle[r][c] ? Material.JUNGLE_PLANKS : Material.BIRCH_PLANKS;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = Y0; y <= Y0 + 2; y++) {
                set(cx(c) + dx, y, cz(r) + dz, wood);
            }
        }
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            if (!box[r][c]) continue;
            for (int[] d : DIRS) {
                int ux = -d[1], uz = -d[0];
                int faceX = cx(c) - 2 * ux, faceZ = cz(r) - 2 * uz;
                int standR = r - d[0], standC = c - d[1];
                boolean standOk = standR == ROWS ? standC >= 0 && standC < COLS : free(standR, standC);
                boolean pushable = standOk && free(r + d[0], c + d[1]);
                BlockFace out = face(-ux, -uz);
                for (int s = -1; s <= 1; s++) for (int y = Y0; y <= Y0 + 2; y++) {
                    int x = faceX + (uz != 0 ? s : 0), z = faceZ + (ux != 0 ? s : 0);
                    Block spot = block(x, y, z);
                    if (!spot.isEmpty()) continue;
                    boolean button = pushable && s == 0 && y == Y0 + 1;
                    String kind = button ? "minecraft:stone_button[face=wall,facing=" : "minecraft:oak_wall_sign[facing=";
                    set(spot, state(kind + out.name().toLowerCase() + "]"));
                }
            }
        }
    }

    private static BlockFace face(int dx, int dz) {
        if (dx > 0) return BlockFace.EAST;
        if (dx < 0) return BlockFace.WEST;
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    @Override
    boolean click(Player player, Block block, boolean right) {
        if (block.getType() == Material.CHEST && is(block, CHEST_X, CHEST_Y, CHEST_Z)) {
            if (right) solved(player, "got through the Boulder room!");
            return true;
        }
        if (block.getType() != Material.STONE_BUTTON) return false;
        int[] at = rel(block);
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            if (!box[r][c]) continue;
            for (int[] d : DIRS) {
                int ux = -d[1], uz = -d[0];
                if (at[0] != cx(c) - 2 * ux || at[2] != cz(r) - 2 * uz || at[1] != Y0 + 1) continue;
                if (!free(r + d[0], c + d[1])) return true;
                box[r][c] = false;
                box[r + d[0]][c + d[1]] = true;
                jungle[r + d[0]][c + d[1]] = jungle[r][c];
                draw();
                world.playSound(block.getLocation(), Sound.BLOCK_PISTON_EXTEND, 0.6f, 1f);
                if (solve(mask()) < 0) {
                    say("§cThe boxes are stuck, resetting.");
                    later(40, this::reset);
                }
                return true;
            }
        }
        return true;
    }
}
