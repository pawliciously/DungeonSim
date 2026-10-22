package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

final class PuzzleTicTacToe extends Puzzle {
    private static final int X = 8;
    private static final char AI = 'X', YOU = 'O';
    private static final byte BACKGROUND = 33, RED = 114, BLUE = 102;
    private static MapView mapX, mapO;

    private final char[] board = new char[9];
    private boolean aiTurn;

    PuzzleTicTacToe(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
    }

    private static int y(int cell) {
        return 72 - cell / 3;
    }

    private static int z(int cell) {
        return 17 - cell % 3;
    }

    @Override
    void begin() {
        java.util.Arrays.fill(board, ' ');
        for (int i = 0; i < 9; i++) set(X, y(i), z(i), state("minecraft:stone_button[face=wall,facing=east]"));
        aiTurn = true;
        later(15, this::aiMove);
    }

    @Override
    boolean click(Player player, Block block, boolean right) {
        if (block.getType() != Material.STONE_BUTTON) return false;
        for (int i = 0; i < 9; i++) {
            if (!is(block, X, y(i), z(i))) continue;
            if (aiTurn || board[i] != ' ') return true;
            place(i, YOU);
            player.playSound(block.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.6f, 1f);
            if (!over(player)) {
                aiTurn = true;
                later(10, () -> {
                    aiMove();
                    over(player);
                });
            }
            return true;
        }
        return false;
    }

    private boolean over(Player player) {
        char w = winner(board);
        if (w == AI) {
            failed(player, "lost Tic Tac Toe!");
            return true;
        }
        if (w == YOU) {
            solved(player, "beat Tic Tac Toe!");
            return true;
        }
        if (full(board)) {
            solved(player, "tied Tic Tac Toe!");
            return true;
        }
        return false;
    }

    private void aiMove() {
        if (winner(board) != ' ' || full(board)) return;
        int best = Integer.MIN_VALUE;
        List<Integer> moves = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (board[i] != ' ') continue;
            board[i] = AI;
            int score = minimax(board, false, 0);
            board[i] = ' ';
            if (score > best) {
                best = score;
                moves.clear();
            }
            if (score == best) moves.add(i);
        }
        place(moves.get(random.nextInt(moves.size())), AI);
        aiTurn = false;
    }

    private static int minimax(char[] b, boolean aiToMove, int depth) {
        char w = winner(b);
        if (w == AI) return 10 - depth;
        if (w == YOU) return depth - 10;
        if (full(b)) return 0;
        int best = aiToMove ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (b[i] != ' ') continue;
            b[i] = aiToMove ? AI : YOU;
            int score = minimax(b, !aiToMove, depth + 1);
            b[i] = ' ';
            best = aiToMove ? Math.max(best, score) : Math.min(best, score);
        }
        return best;
    }

    private static final int[][] LINES = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};

    private static char winner(char[] b) {
        for (int[] l : LINES) if (b[l[0]] != ' ' && b[l[0]] == b[l[1]] && b[l[1]] == b[l[2]]) return b[l[0]];
        return ' ';
    }

    private static boolean full(char[] b) {
        for (char c : b) if (c == ' ') return false;
        return true;
    }

    private void place(int cell, char mark) {
        board[cell] = mark;
        Block spot = block(X, y(cell), z(cell));
        set(spot, Material.AIR.createBlockData());
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(mark == AI ? mapX() : mapO());
        map.setItemMeta(meta);
        BlockFace facing = frame.face(BlockFace.EAST);
        spawn(spot.getLocation(), ItemFrame.class, f -> {
            f.setFacingDirection(facing, true);
            f.setItem(map, false);
            f.setFixed(true);
        });
        world.playSound(spot.getLocation(), Sound.ENTITY_ITEM_FRAME_ADD_ITEM, 0.6f, 1f);
    }

    private MapView mapX() {
        if (mapX == null) mapX = map(true);
        return mapX;
    }

    private MapView mapO() {
        if (mapO == null) mapO = map(false);
        return mapO;
    }

    private MapView map(boolean cross) {
        MapView view = Bukkit.createMap(Bukkit.getWorlds().getFirst());
        for (MapRenderer r : List.copyOf(view.getRenderers())) view.removeRenderer(r);
        view.setTrackingPosition(false);
        view.setLocked(true);
        byte[] pixels = new byte[128 * 128];
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                byte c = BACKGROUND;
                if (cross) {
                    if ((Math.abs(x - y) < 9 || Math.abs(x + y - 127) < 9) && x > 16 && x < 111 && y > 16 && y < 111) c = RED;
                } else {
                    double d = Math.hypot(x - 63.5, y - 63.5);
                    if (d > 30 && d < 46) c = BLUE;
                }
                pixels[y * 128 + x] = c;
            }
        }
        view.addRenderer(new MapRenderer() {
            private boolean drawn;

            @Override
            @SuppressWarnings("deprecation")
            public void render(MapView map, MapCanvas canvas, Player player) {
                if (drawn) return;
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) canvas.setPixel(x, y, pixels[y * 128 + x]);
                drawn = true;
            }
        });
        return view;
    }
}
