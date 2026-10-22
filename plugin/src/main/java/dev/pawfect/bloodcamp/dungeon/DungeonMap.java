package dev.pawfect.bloodcamp.dungeon;

import dev.pawfect.bloodcamp.gen.Layout;
import dev.pawfect.bloodcamp.gen.Layout.DoorType;
import dev.pawfect.bloodcamp.gen.Layout.RoomType;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class DungeonMap extends MapRenderer {
    private static final int CORNER = 5, ROOM = 16, STEP = 20;
    private static final byte UNOPENED = 85, QUESTION = 119, WHITE_CHECK = 34, GREEN_CHECK = 30;
    private static final byte WITHER_DOOR = 119, BLOOD_DOOR = 18;
    private static final int[][] QUESTION_MARK = {{5, 6}, {5, 7}, {5, 8}, {6, 5}, {6, 9}, {7, 9}, {8, 8}, {9, 7}, {10, 7}, {12, 7}, {13, 7}};
    private static final int[][] CHECKMARK = {
        {4, 11}, {4, 12}, {5, 10}, {5, 11}, {5, 12}, {6, 9}, {6, 10}, {6, 11}, {7, 8}, {7, 9}, {7, 10},
        {8, 4}, {8, 5}, {8, 7}, {8, 8}, {8, 9}, {9, 4}, {9, 5}, {9, 6}, {9, 7}, {9, 8},
        {10, 4}, {10, 5}, {10, 6}, {10, 7}, {11, 5}, {11, 6}, {11, 7}, {12, 5}, {12, 6}};

    private final DungeonPlan plan;
    private final Layout layout;
    private final DungeonSecrets secrets;
    private final boolean[] doorOpen;
    private final boolean[] opened;
    private final boolean[] cleared;
    private final int[] depth;
    private final int[] placementOf;
    private final Map<Integer, List<DoorType>> keys = new HashMap<>();
    private boolean revealed;
    java.util.function.IntConsumer onOpen = room -> { };
    private final Map<UUID, Integer> drawnFor = new HashMap<>();
    private byte[] pixels;
    private int pixelsKey = -1;
    private int version;
    private MapView view;

    DungeonMap(DungeonPlan plan, DungeonSecrets secrets, boolean[] doorOpen) {
        super(true);
        this.plan = plan;
        this.layout = plan.layout;
        this.secrets = secrets;
        this.doorOpen = doorOpen;
        this.opened = new boolean[layout.rooms.size()];
        this.cleared = new boolean[layout.rooms.size()];
        this.placementOf = new int[layout.rooms.size()];
        for (int i = 0; i < plan.placements.size(); i++) placementOf[plan.placements.get(i).room().id()] = i;
        this.depth = depths();
        for (Layout.Door door : layout.doors) {
            if (door.type() != DoorType.WITHER && door.type() != DoorType.BLOOD) continue;
            int near = depth[door.roomA()] < depth[door.roomB()] ? door.roomA() : door.roomB();
            keys.computeIfAbsent(near, k -> new java.util.ArrayList<>()).add(door.type());
        }
    }

    List<DoorType> keysIn(int room) {
        return keys.getOrDefault(room, List.of());
    }

    private int[] depths() {
        int[] d = new int[layout.rooms.size()];
        Arrays.fill(d, -1);
        int entrance = find(RoomType.ENTRANCE);
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(entrance));
        d[entrance] = 0;
        while (!queue.isEmpty()) {
            int room = queue.poll();
            for (Layout.Door door : layout.doors) {
                int other = door.roomA() == room ? door.roomB() : door.roomB() == room ? door.roomA() : -1;
                if (other >= 0 && d[other] < 0) {
                    d[other] = d[room] + 1;
                    queue.add(other);
                }
            }
        }
        return d;
    }

    private int find(RoomType type) {
        for (Layout.Room r : layout.rooms) if (r.type() == type) return r.id();
        return 0;
    }

    void reset() {
        Arrays.fill(opened, false);
        Arrays.fill(cleared, false);
        version++;
    }

    void start() {
        int entrance = find(RoomType.ENTRANCE);
        open(entrance);
        for (Layout.Door door : layout.doors) {
            if (door.type() == DoorType.ENTRANCE) open(door.roomA() == entrance ? door.roomB() : door.roomA());
        }
    }

    void open(int room) {
        if (!opened[room]) {
            opened[room] = true;
            version++;
            onOpen.accept(room);
        }
    }

    void enter(int room) {
        open(room);
        RoomType type = layout.rooms.get(room).type();
        if (cleared[room] || type == RoomType.ENTRANCE || type == RoomType.BLOOD || type == RoomType.PUZZLE) return;
        cleared[room] = true;
        version++;
    }

    int openedCount() {
        int n = 0;
        for (boolean b : opened) if (b) n++;
        return n;
    }

    int clearedCount() {
        int n = 0;
        for (boolean b : cleared) if (b) n++;
        return n;
    }

    int clearable() {
        int n = 0;
        for (Layout.Room r : layout.rooms) if (r.type() != RoomType.ENTRANCE && r.type() != RoomType.BLOOD && r.type() != RoomType.PUZZLE) n++;
        return n;
    }

    void solvePuzzle(int room) {
        if (cleared[room]) return;
        cleared[room] = true;
        version++;
    }

    boolean isOpened(int room) {
        return opened[room];
    }

    boolean isRevealed() {
        return revealed;
    }

    void reveal() {
        revealed = true;
        version++;
    }

    private boolean shown(int room) {
        return revealed || opened[room];
    }

    ItemStack item(JavaPlugin plugin, World world) {
        if (view == null) {
            view = Bukkit.createMap(world);
            for (MapRenderer r : List.copyOf(view.getRenderers())) view.removeRenderer(r);
            view.setScale(MapView.Scale.NORMAL);
            view.setCenterX(0);
            view.setCenterZ(0);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            view.addRenderer(this);
        }
        ItemStack stack = Bukkit.getItemFactory().createItemStack("minecraft:filled_map[minecraft:custom_data={id:\"MAP\"}]");
        MapMeta meta = (MapMeta) stack.getItemMeta();
        meta.setMapView(view);
        meta.displayName(Component.text("Magical Map", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Shows the layout of the Dungeon as", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("it is explored and completed.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(DungeonSecrets.itemTag(plugin), PersistentDataType.BYTE, (byte) 4);
        stack.setItemMeta(meta);
        return stack;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void render(MapView map, MapCanvas canvas, Player player) {
        int key = version * 31 + secrets.totalFound();
        if (key != pixelsKey) {
            pixels = draw();
            pixelsKey = key;
        }
        Integer drawn = drawnFor.get(player.getUniqueId());
        if (drawn == null || drawn != key) {
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) canvas.setPixel(x, y, pixels[y * 128 + x]);
            drawnFor.put(player.getUniqueId(), key);
        }

        MapCursorCollection cursors = new MapCursorCollection();
        for (Player p : player.getWorld().getPlayers()) {
            Location at = p.getLocation();
            byte x = cursorCoord(at.getX()), y = cursorCoord(at.getZ());
            float yaw = ((at.getYaw() % 360) + 360) % 360;
            byte rot = (byte) (Math.round(yaw * 16 / 360f) & 15);
            cursors.addCursor(new MapCursor(x, y, rot, p == player ? MapCursor.Type.FRAME : MapCursor.Type.BLUE_MARKER, true));
        }
        canvas.setCursors(cursors);
    }

    private static byte cursorCoord(double world) {
        double px = CORNER + (world - DungeonPlan.GRID) * STEP / DungeonPlan.CELL;
        return (byte) Math.max(-128, Math.min(127, Math.round(px * 2 - 128)));
    }

    private byte[] draw() {
        byte[] out = new byte[128 * 128];
        int w = layout.width;

        for (Layout.Room room : layout.rooms) {
            if (!shown(room.id())) continue;
            byte color = color(room.type());
            for (int cell : room.cells()) {
                int cx = cell % w, cz = cell / w;
                fill(out, px(cx), px(cz), ROOM, ROOM, color);
                if (cx + 1 < w && layout.roomOf[cell + 1] == room.id()) fill(out, px(cx) + ROOM, px(cz), STEP - ROOM, ROOM, color);
                if (cz + 1 < layout.height && layout.roomOf[cell + w] == room.id()) fill(out, px(cx), px(cz) + ROOM, ROOM, STEP - ROOM, color);
                if (cx + 1 < w && cz + 1 < layout.height && layout.roomOf[cell + 1] == room.id()
                    && layout.roomOf[cell + w] == room.id() && layout.roomOf[cell + w + 1] == room.id()) {
                    fill(out, px(cx) + ROOM, px(cz) + ROOM, STEP - ROOM, STEP - ROOM, color);
                }
            }
        }

        for (int i = 0; i < layout.doors.size(); i++) {
            Layout.Door door = layout.doors.get(i);
            boolean a = shown(door.roomA()), b = shown(door.roomB());
            if (!a && !b) continue;
            int cellA = Math.min(door.cellA(), door.cellB()), cellB = Math.max(door.cellA(), door.cellB());
            byte color;
            if (door.type() == DoorType.WITHER && !doorOpen[i]) color = WITHER_DOOR;
            else if (door.type() == DoorType.BLOOD && !doorOpen[i]) color = BLOOD_DOOR;
            else if (!a || !b) color = UNOPENED;
            else {
                int child = depth[door.roomA()] > depth[door.roomB()] ? door.roomA() : door.roomB();
                color = color(layout.rooms.get(child).type());
            }
            int cx = cellA % w, cz = cellA / w;
            if (cellB == cellA + 1) fill(out, px(cx) + ROOM, px(cz) + 6, STEP - ROOM, 5, color);
            else fill(out, px(cx) + 6, px(cz) + ROOM, 5, STEP - ROOM, color);

            if (!a || !b) {
                int hidden = a ? door.cellB() : door.cellA();
                int hx = px(hidden % w), hz = px(hidden / w);
                fill(out, hx, hz, ROOM, ROOM, UNOPENED);
                for (int[] p : QUESTION_MARK) out[(hz + p[0]) * 128 + hx + p[1]] = QUESTION;
            }
        }

        for (Layout.Room room : layout.rooms) {
            if (!cleared[room.id()]) continue;
            int placement = placementOf[room.id()];
            byte check = secrets.found(placement) >= secrets.total(placement) ? GREEN_CHECK : WHITE_CHECK;
            int first = Arrays.stream(room.cells()).min().orElse(0);
            int x0 = px(first % w), z0 = px(first / w);
            for (int[] p : CHECKMARK) out[(z0 + p[0]) * 128 + x0 + p[1]] = check;
        }
        return out;
    }

    private static int px(int cell) {
        return CORNER + cell * STEP;
    }

    private static void fill(byte[] out, int x, int y, int w, int h, byte color) {
        for (int dy = 0; dy < h; dy++) {
            int row = y + dy;
            if (row < 0 || row >= 128) continue;
            for (int dx = 0; dx < w; dx++) {
                int col = x + dx;
                if (col >= 0 && col < 128) out[row * 128 + col] = color;
            }
        }
    }

    private static byte color(RoomType type) {
        return switch (type) {
            case ENTRANCE -> 30;
            case BLOOD -> 18;
            case FAIRY -> 82;
            case PUZZLE -> 66;
            case TRAP -> 62;
            case MINIBOSS -> 74;
            case NORMAL -> 63;
        };
    }
}
