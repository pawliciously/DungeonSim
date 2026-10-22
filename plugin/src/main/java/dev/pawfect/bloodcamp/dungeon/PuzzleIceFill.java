package dev.pawfect.bloodcamp.dungeon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

final class PuzzleIceFill extends Puzzle {
    private static final class Board {
        final int ox, floorY, oz, size;
        final boolean[][] blocked;
        final boolean[][] visited;
        int free, count;
        boolean done;

        Board(int ox, int floorY, int oz, int size) {
            this.ox = ox;
            this.floorY = floorY;
            this.oz = oz;
            this.size = size;
            this.blocked = new boolean[size][size];
            this.visited = new boolean[size][size];
        }
    }

    private final Board[] boards = {new Board(16, 69, 9, 3), new Board(17, 70, 16, 5), new Board(18, 71, 25, 7)};
    private final Map<UUID, int[]> last = new HashMap<>();

    PuzzleIceFill(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
    }

    @Override
    void begin() {
        for (Board b : boards) {
            b.free = 0;
            for (int row = 0; row < b.size; row++) for (int col = 0; col < b.size; col++) {
                b.blocked[row][col] = !block(b.ox - col, b.floorY + 1, b.oz - row).isEmpty();
                if (!b.blocked[row][col]) b.free++;
            }
            clear(b);
            b.done = false;
        }
    }

    private void clear(Board b) {
        for (int row = 0; row < b.size; row++) for (int col = 0; col < b.size; col++) {
            b.visited[row][col] = false;
            if (!b.blocked[row][col]) set(b.ox - col, b.floorY, b.oz - row, Material.ICE);
        }
        b.count = 0;
    }

    @Override
    void end() {
        last.clear();
    }

    @Override
    void tick() {
        for (Player p : playersInside()) {
            double[] r = rel(p.getLocation());
            int rx = (int) Math.floor(r[0]), rz = (int) Math.floor(r[2]);
            int index = -1, row = 0, col = 0;
            for (int i = 0; i < boards.length; i++) {
                Board b = boards[i];
                double feet = r[1] - (b.floorY + 1);
                if (feet < -0.01 || feet > 0.1) continue;
                col = b.ox - rx;
                row = b.oz - rz;
                if (row >= 0 && row < b.size && col >= 0 && col < b.size && !b.blocked[row][col]) {
                    index = i;
                    break;
                }
            }
            int[] prev = last.get(p.getUniqueId());
            if (index < 0) {
                continue;
            }
            Board b = boards[index];
            if (prev != null && prev[0] == index && prev[1] == row && prev[2] == col) continue;
            last.put(p.getUniqueId(), new int[]{index, row, col});
            if (b.done) continue;
            boolean adjacent = prev != null && prev[0] == index && Math.abs(prev[1] - row) + Math.abs(prev[2] - col) == 1;
            if (b.count == 0) {
                if (row != b.size - 1 || col != b.size / 2) continue;
            } else if (b.visited[row][col] || !adjacent) {
                String why = b.visited[row][col] ? "You stepped on your path, board reset" : "Diagonal step, board reset";
                clear(b);
                p.sendActionBar(Component.text(why, NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.6f, 0.8f);
                if (row != b.size - 1 || col != b.size / 2) continue;
            }
            b.visited[row][col] = true;
            b.count++;
            set(b.ox - col, b.floorY, b.oz - row, Material.PACKED_ICE);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.2f + 0.8f * b.count / b.free);
            if (b.count == b.free) {
                b.done = true;
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.5f);
                int finished = 0;
                for (Board other : boards) if (other.done) finished++;
                if (finished == boards.length) solved(p, "completed Ice Fill!");
                else p.sendActionBar(Component.text("Board " + finished + "/" + boards.length, NamedTextColor.AQUA));
            }
        }
    }
}
