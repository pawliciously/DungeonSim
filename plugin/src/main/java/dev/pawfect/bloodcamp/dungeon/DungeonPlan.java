package dev.pawfect.bloodcamp.dungeon;

import dev.pawfect.bloodcamp.gen.Layout;
import dev.pawfect.bloodcamp.gen.Layout.DoorType;
import dev.pawfect.bloodcamp.gen.Layout.RoomType;
import dev.pawfect.bloodcamp.gen.Layout.Shape;
import dev.pawfect.bloodcamp.gen.Rng;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;

final class DungeonPlan {
    static final int GRID = -200;
    static final int CELL = 32;
    static final int DOOR_Y = 69;

    record Placement(Layout.Room room, RoomData data, int turns, int cornerX, int cornerZ) {
        int[] toWorld(int rx, int rz) {
            return switch (turns) {
                case 0 -> new int[]{rx + cornerX, rz + cornerZ};
                case 1 -> new int[]{-rz + cornerX, rx + cornerZ};
                case 2 -> new int[]{-rx + cornerX, -rz + cornerZ};
                default -> new int[]{rz + cornerX, -rx + cornerZ};
            };
        }

        BlockData at(int x, int y, int z) {
            int rx, rz;
            switch (turns) {
                case 0 -> { rx = x - cornerX; rz = z - cornerZ; }
                case 1 -> { rx = z - cornerZ; rz = -x + cornerX; }
                case 2 -> { rx = -x + cornerX; rz = -z + cornerZ; }
                default -> { rx = -z + cornerZ; rz = x - cornerX; }
            }
            return data.at(rx, y, rz, turns);
        }
    }

    record Door(int x, int z, boolean alongX, DoorType type, int roomA, int roomB) {
        boolean inMiddle(int bx, int by, int bz) {
            return Math.abs(bx - x) <= 1 && Math.abs(bz - z) <= 1 && by >= DOOR_Y && by <= DOOR_Y + 3;
        }
    }

    enum SecretKind {
        CHEST, ITEM, BAT, WITHER, KEY, KEY_SLOT;

        boolean counted() {
            return this == CHEST || this == ITEM || this == BAT || this == WITHER;
        }
    }

    record Secret(SecretKind kind, int x, int y, int z, int placement, String name) {}

    record Head(int x, int y, int z, HeadProfile profile) {}

    static final class Writes {
        int size;
        int[] xyz = new int[3 * 1024];
        BlockData[] blocks = new BlockData[1024];

        void add(int x, int y, int z, BlockData block) {
            if (size == blocks.length) {
                blocks = Arrays.copyOf(blocks, size * 2);
                xyz = Arrays.copyOf(xyz, size * 6);
            }
            xyz[size * 3] = x;
            xyz[size * 3 + 1] = y;
            xyz[size * 3 + 2] = z;
            blocks[size++] = block;
        }
    }

    private static final BlockData AIR = Material.AIR.createBlockData();
    private static final Set<Material> DOOR_BLOCKS = Set.of(
        Material.COAL_BLOCK, Material.RED_TERRACOTTA, Material.INFESTED_CHISELED_STONE_BRICKS, Material.BARRIER);
    private static final int[][] SIDE_CENTRE = {{15, 0}, {30, 15}, {15, 30}, {0, 15}};
    private static final int[][] SIDE_ALONG = {{1, 0}, {0, 1}, {1, 0}, {0, 1}};
    private static final int[][] SIDE_IN = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};

    final Layout layout;
    final List<Placement> placements = new ArrayList<>();
    final List<Door> doors = new ArrayList<>();
    final Writes writes = new Writes();
    final int entranceDoor;
    double spawnX, spawnY, spawnZ;
    float spawnYaw;
    double mortX, mortY, mortZ;
    float mortYaw;
    final int bloodMinX, bloodMinZ;
    final List<Secret> secrets = new ArrayList<>();
    final List<Head> heads = new ArrayList<>();
    final int[] secretTotal;
    int mimic = -1;

    private final RoomLibrary library;
    private final Rng rng;
    private final Placement[] byRoom;

    DungeonPlan(Layout layout, RoomLibrary library) throws IOException {
        this.layout = layout;
        this.library = library;
        this.rng = new Rng(layout.seed.rngSeed() ^ 0x5DEECE66DL);
        this.byRoom = new Placement[layout.rooms.size()];

        for (Layout.Door d : layout.doors) doors.add(door(d));
        int entrance = -1;
        for (int i = 0; i < doors.size(); i++) if (doors.get(i).type() == DoorType.ENTRANCE) entrance = i;
        entranceDoor = entrance;

        Set<String> used = new HashSet<>();
        for (Layout.Room room : layout.rooms) {
            Placement p = place(room, used);
            placements.add(p);
            byRoom[room.id()] = p;
        }

        for (Placement p : placements) writeRoom(p);
        for (Placement p : placements) patchUnusedDoorways(p);
        List<BlockData[]> frames = library.doorFrames();
        for (Door d : doors) writeDoor(d, frames);
        placeSpawn();
        secretTotal = new int[placements.size()];
        placeSecrets();

        int bloodCell = cellsOf(find(RoomType.BLOOD))[0];
        bloodMinX = cellX(bloodCell);
        bloodMinZ = cellZ(bloodCell);
    }

    private Layout.Room find(RoomType type) {
        for (Layout.Room r : layout.rooms) if (r.type() == type) return r;
        throw new IllegalStateException("Layout has no " + type);
    }

    int placementAt(int x, int z) {
        int cx = Math.floorDiv(x - GRID, CELL), cz = Math.floorDiv(z - GRID, CELL);
        if (cx < 0 || cz < 0 || cx >= layout.width || cz >= layout.height) return -1;
        int room = layout.roomOf[cz * layout.width + cx];
        for (int i = 0; i < placements.size(); i++) if (placements.get(i).room().id() == room) return i;
        return -1;
    }

    int roomIdAt(int x, int z) {
        int rx = x - GRID, rz = z - GRID;
        if (rx < 0 || rz < 0) return -1;
        int cx = rx / CELL, cz = rz / CELL;
        if (cx >= layout.width || cz >= layout.height) return -1;
        int room = layout.roomOf[cz * layout.width + cx];
        if (rx % CELL == CELL - 1 && (cx + 1 >= layout.width || layout.roomOf[cz * layout.width + cx + 1] != room)) return -1;
        if (rz % CELL == CELL - 1 && (cz + 1 >= layout.height || layout.roomOf[(cz + 1) * layout.width + cx] != room)) return -1;
        if (rx % CELL == CELL - 1 && rz % CELL == CELL - 1
            && (cx + 1 >= layout.width || cz + 1 >= layout.height || layout.roomOf[(cz + 1) * layout.width + cx + 1] != room)) return -1;
        return room;
    }

    boolean insideRoom(int room, int x, int z) {
        return roomIdAt(x, z) == room && roomIdAt(x + 1, z) == room && roomIdAt(x - 1, z) == room
            && roomIdAt(x, z + 1) == room && roomIdAt(x, z - 1) == room;
    }

    Placement placementOf(int room) {
        return byRoom[room];
    }

    int cellX(int cell) {
        return GRID + (cell % layout.width) * CELL;
    }

    int cellZ(int cell) {
        return GRID + (cell / layout.width) * CELL;
    }

    private int[] cellsOf(Layout.Room room) {
        return room.cells();
    }

    private Door door(Layout.Door d) {
        int a = Math.min(d.cellA(), d.cellB()), b = Math.max(d.cellA(), d.cellB());
        boolean alongX = b == a + 1;
        int x = alongX ? cellX(a) + 31 : cellX(a) + 15;
        int z = alongX ? cellZ(a) + 15 : cellZ(a) + 31;
        return new Door(x, z, alongX, d.type(), d.roomA(), d.roomB());
    }

    private Placement place(Layout.Room room, Set<String> used) throws IOException {
        String pool = switch (room.type()) {
            case NORMAL -> switch (room.shape()) {
                case S1X1 -> "1x1";
                case S1X2 -> "1x2";
                case S1X3 -> "1x3";
                case S1X4 -> "1x4";
                case S2X2 -> "2x2";
                case L -> "l-shape";
            };
            case PUZZLE -> "puzzle";
            case TRAP -> "trap";
            case MINIBOSS -> "miniboss";
            case ENTRANCE, BLOOD, FAIRY -> "special";
        };
        List<RoomLibrary.Entry> options = new ArrayList<>(library.pool(pool));
        if (pool.equals("special")) {
            String want = room.type().name().toLowerCase();
            options.removeIf(e -> !e.id().equals(want));
        }
        if (options.isEmpty()) throw new IllegalStateException("No " + (pool.equals("special") ? room.type().name().toLowerCase() : pool) + " rooms uploaded");
        if (room.type() == RoomType.NORMAL && room.shape() == Shape.S1X1) return placeOneByOne(room, options, used);
        List<RoomLibrary.Entry> fresh = options.stream().filter(e -> !used.contains(e.id())).toList();
        RoomLibrary.Entry pick = rng.pick(fresh.isEmpty() ? options : fresh);
        used.add(pick.id());
        RoomData data = library.load(pick);

        int[] cells = room.cells();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int c : cells) {
            minX = Math.min(minX, cellX(c));
            maxX = Math.max(maxX, cellX(c));
            minZ = Math.min(minZ, cellZ(c));
            maxZ = Math.max(maxZ, cellZ(c));
        }
        int turns = turnsFor(room, data, cells, minX, minZ, maxX, maxZ);
        int cornerX = (turns == 1 || turns == 2) ? maxX + 30 : minX;
        int cornerZ = (turns == 2 || turns == 3) ? maxZ + 30 : minZ;
        return new Placement(room, data, turns, cornerX, cornerZ);
    }

    private Placement placeOneByOne(Layout.Room room, List<RoomLibrary.Entry> options, Set<String> used) throws IOException {
        int cell = room.cells()[0];
        int want = 0;
        for (int side = 0; side < 4; side++) {
            int n = neighbour(cell, side);
            if (n >= 0 && layout.doorBetween(cell, n) != null) want |= 1 << side;
        }
        record Fit(RoomLibrary.Entry entry, RoomData data, int turns, int score) {}
        List<Fit> best = new ArrayList<>();
        int bestScore = Integer.MIN_VALUE;
        for (boolean freshOnly : new boolean[]{true, false}) {
            for (RoomLibrary.Entry entry : options) {
                if (freshOnly && used.contains(entry.id())) continue;
                RoomData data = library.load(entry);
                int open = openMask(data);
                for (int t = 0; t < 4; t++) {
                    int turned = ((open << t) | (open >> (4 - t))) & 15;
                    int score = turned == want ? 100 : Integer.bitCount(turned & want) * 10 - Integer.bitCount(turned & ~want);
                    if (score > bestScore) {
                        bestScore = score;
                        best.clear();
                    }
                    if (score == bestScore) best.add(new Fit(entry, data, t, score));
                }
            }
            if (bestScore == 100) break;
        }
        Fit fit = rng.pick(best);
        used.add(fit.entry().id());
        return new Placement(room, fit.data(), fit.turns(), (fit.turns() == 1 || fit.turns() == 2) ? cellX(cell) + 30 : cellX(cell),
            (fit.turns() == 2 || fit.turns() == 3) ? cellZ(cell) + 30 : cellZ(cell));
    }

    private static int openMask(RoomData data) {
        int mask = data.openMask;
        if (mask >= 0) return mask;
        mask = 0;
        for (int side = 0; side < 4; side++) {
            if (doorwayScore((x, y, z) -> data.at(x, y, z, 0), 0, 0, side, false) >= 4) mask |= 1 << side;
        }
        data.openMask = mask;
        return mask;
    }

    private int turnsFor(Layout.Room room, RoomData data, int[] cells, int minX, int minZ, int maxX, int maxZ) {
        Shape shape = room.shape();
        if (shape == Shape.S1X2 || shape == Shape.S1X3 || shape == Shape.S1X4) {
            boolean horizontal = maxX > minX;
            return (horizontal ? 0 : 1) + (rng.chance(0.5) ? 2 : 0);
        }
        if (shape == Shape.L) {
            Set<Long> present = new HashSet<>();
            for (int c : cells) present.add(key(cellX(c), cellZ(c)));
            if (!present.contains(key(minX, minZ))) return 3;
            if (!present.contains(key(minX, maxZ))) return 2;
            if (!present.contains(key(maxX, minZ))) return 0;
            return 1;
        }
        boolean oneDoor = room.type() != RoomType.NORMAL && room.type() != RoomType.FAIRY;
        if (oneDoor) {
            int relSide = doorSide(data);
            int worldSide = worldDoorSide(room);
            if (relSide >= 0 && worldSide >= 0) return Math.floorMod(worldSide - relSide, 4);
        }
        return rng.nextInt(4);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int doorSide(RoomData data) {
        int best = -1;
        double bestScore = 3.5;
        for (int side = 0; side < 4; side++) {
            final int s = side;
            double score = doorwayScore((x, y, z) -> data.at(x, y, z, 0), 0, 0, s, true);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private int worldDoorSide(Layout.Room room) {
        int cell = room.cells()[0];
        for (Layout.Door d : layout.doors) {
            int other;
            if (d.cellA() == cell) other = d.cellB();
            else if (d.cellB() == cell) other = d.cellA();
            else continue;
            if (other == cell - layout.width) return 0;
            if (other == cell + 1) return 1;
            if (other == cell + layout.width) return 2;
            if (other == cell - 1) return 3;
        }
        return -1;
    }

    @FunctionalInterface
    private interface Lookup {
        BlockData at(int x, int y, int z);
    }

    private static double doorwayScore(Lookup lookup, int baseX, int baseZ, int side, boolean preferDoors) {
        double score = 0;
        for (int w = -1; w <= 1; w++) {
            int[] wall = sidePos(baseX, baseZ, side, w, 0);
            BlockData front = lookup.at(wall[0], DOOR_Y + 1, wall[1]);
            if (front == null) score += 1;
            else if (DOOR_BLOCKS.contains(front.getMaterial())) score += preferDoors ? 1.5 : 1;
            int[] behind = sidePos(baseX, baseZ, side, w, 1);
            if (lookup.at(behind[0], DOOR_Y + 1, behind[1]) == null) score += 1;
        }
        return score;
    }

    private static int[] sidePos(int baseX, int baseZ, int side, int along, int depth) {
        return new int[]{
            baseX + SIDE_CENTRE[side][0] + SIDE_ALONG[side][0] * along + SIDE_IN[side][0] * depth,
            baseZ + SIDE_CENTRE[side][1] + SIDE_ALONG[side][1] * along + SIDE_IN[side][1] * depth,
        };
    }

    private void writeRoom(Placement p) {
        RoomData d = p.data();
        for (int y = 0; y < d.sy; y++) {
            for (int z = 0; z < d.sz; z++) {
                for (int x = 0; x < d.sx; x++) {
                    int index = d.data[(y * d.sz + z) * d.sx + x];
                    if (d.isAir(index)) continue;
                    int[] world = p.toWorld(d.ox + x, d.oz + z);
                    writes.add(world[0], d.oy + y, world[1], d.state(index, p.turns()));
                }
            }
        }
    }

    private void patchUnusedDoorways(Placement p) {
        Layout.Room room = p.room();
        for (int cell : room.cells()) {
            int bx = cellX(cell), bz = cellZ(cell);
            for (int side = 0; side < 4; side++) {
                int neighbour = neighbour(cell, side);
                if (neighbour >= 0 && layout.roomOf[neighbour] == room.id()) continue;
                if (neighbour >= 0 && layout.doorBetween(cell, neighbour) != null) continue;
                if (doorwayScore(p::at, bx, bz, side, false) < 4) continue;
                for (int depth = 0; depth <= 2; depth++) {
                    for (int y = DOOR_Y; y <= DOOR_Y + 4; y++) {
                        for (int w = -2; w <= 2; w++) {
                            int[] src = sidePos(bx, bz, side, w <= 0 ? -3 : 3, depth);
                            int[] dst = sidePos(bx, bz, side, w, depth);
                            BlockData block = p.at(src[0], y, src[1]);
                            writes.add(dst[0], y, dst[1], block == null ? AIR : block);
                        }
                    }
                }
            }
        }
    }

    private int neighbour(int cell, int side) {
        int x = cell % layout.width, z = cell / layout.width;
        return switch (side) {
            case 0 -> z > 0 ? cell - layout.width : -1;
            case 1 -> x < layout.width - 1 ? cell + 1 : -1;
            case 2 -> z < layout.height - 1 ? cell + layout.width : -1;
            default -> x > 0 ? cell - 1 : -1;
        };
    }

    private void writeDoor(Door d, List<BlockData[]> frames) {
        int dx = d.alongX() ? 3 : 2, dz = d.alongX() ? 2 : 3;
        fill(Material.BEDROCK.createBlockData(), d.x() - dx, 66, d.z() - dz, d.x() + dx, 67, d.z() + dz);
        int sx = d.alongX() ? 0 : 2, sz = d.alongX() ? 2 : 0;
        fill(Material.STONE.createBlockData(), d.x() - sx, 68, d.z() - sz, d.x() + sx, 68, d.z() + sz);
        fill(AIR, d.x() - dx, DOOR_Y, d.z() - dz, d.x() + dx, DOOR_Y + 4, d.z() + dz);

        if (!frames.isEmpty()) {
            int index = switch (d.type()) {
                case BLOOD -> 0;
                case ENTRANCE -> Math.min(1, frames.size() - 1);
                default -> frames.size() > 1 ? 1 + rng.nextInt(frames.size() - 1) : 0;
            };
            BlockData[] frame = frames.get(index);
            for (int i = 0; i < frame.length; i++) {
                int ox = i % 5 - 2, oy = i / 25, oz = (i / 5) % 5 - 2;
                BlockData block = frame[i];
                if (d.alongX()) {
                    int t = ox;
                    ox = -oz;
                    oz = t;
                    block = block.clone();
                    block.rotate(StructureRotation.CLOCKWISE_90);
                }
                writes.add(d.x() + ox, DOOR_Y + oy, d.z() + oz, block);
            }
        }
        fillMiddle(d, closedBlock(d.type()));
    }

    static BlockData closedBlock(DoorType type) {
        return switch (type) {
            case NORMAL -> AIR;
            case ENTRANCE -> Material.INFESTED_CHISELED_STONE_BRICKS.createBlockData();
            case WITHER -> Material.COAL_BLOCK.createBlockData();
            case BLOOD -> Material.RED_TERRACOTTA.createBlockData();
        };
    }

    void fillMiddle(Door d, BlockData block) {
        fill(block, d.x() - 1, DOOR_Y, d.z() - 1, d.x() + 1, DOOR_Y + 3, d.z() + 1);
    }

    private void fill(BlockData block, int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) writes.add(x, y, z, block);
    }

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private void placeSecrets() {
        Rng secretRng = new Rng(layout.seed.rngSeed() ^ 0x2545F4914F6CDD1DL);
        List<Integer> itemChests = new ArrayList<>();
        for (int pi = 0; pi < placements.size(); pi++) {
            Placement p = placements.get(pi);
            for (RoomData.Head h : p.data().heads) {
                int[] w = p.toWorld(h.rx(), h.rz());
                heads.add(new Head(w[0], h.y(), w[1], h.profile()));
            }
            RoomLibrary.RoomSecrets room = library.secrets(p.data().room);
            if (room == null) continue;
            secretTotal[pi] = room.count();

            java.util.Map<String, List<RoomLibrary.Secret>> keys = new java.util.LinkedHashMap<>();
            for (RoomLibrary.Secret s : room.secrets()) {
                int[] w = p.toWorld(s.x(), s.z());
                int x = w[0], y = s.y(), z = w[1];
                switch (s.category()) {
                    case "chest" -> {
                        BlockData there = p.at(x, y, z);
                        if (there == null) writes.add(x, y, z, Bukkit.createBlockData("minecraft:chest[facing=" + chestFacing(p, x, y, z) + "]"));
                        if (s.itemChest()) itemChests.add(secrets.size());
                        secrets.add(new Secret(SecretKind.CHEST, x, y, z, pi, s.name()));
                    }
                    case "wither" -> {
                        if (p.at(x, y, z) == null) writes.add(x, y, z, head(secretRng));
                        heads.add(new Head(x, y, z, HeadProfile.WITHER_ESSENCE));
                        secrets.add(new Secret(SecretKind.WITHER, x, y, z, pi, s.name()));
                    }
                    case "item" -> secrets.add(new Secret(SecretKind.ITEM, x, y, z, pi, s.name()));
                    case "bat" -> secrets.add(new Secret(SecretKind.BAT, x, y, z, pi, s.name()));
                    case "key" -> {
                        if (s.name().contains("Place")) secrets.add(new Secret(SecretKind.KEY_SLOT, x, y, z, pi, s.name()));
                        else keys.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s);
                    }
                    default -> { }
                }
            }
            for (List<RoomLibrary.Secret> options : keys.values()) {
                RoomLibrary.Secret s = secretRng.pick(options);
                int[] w = p.toWorld(s.x(), s.z());
                writes.add(w[0], s.y(), w[1], head(secretRng));
                heads.add(new Head(w[0], s.y(), w[1], HeadProfile.REDSTONE_KEY));
                secrets.add(new Secret(SecretKind.KEY, w[0], s.y(), w[1], pi, s.name()));
            }
        }
        if (itemChests.isEmpty()) return;
        mimic = secretRng.pick(itemChests);
        Secret s = secrets.get(mimic);
        writes.add(s.x(), s.y(), s.z(), trappedChest(placements.get(s.placement()), s.x(), s.y(), s.z()));
    }

    private static BlockData trappedChest(Placement p, int x, int y, int z) {
        BlockData there = p.at(x, y, z);
        BlockData chest = there != null && there.getMaterial() == Material.CHEST
            ? Bukkit.createBlockData(there.getAsString().replace("minecraft:chest", "minecraft:trapped_chest"))
            : Bukkit.createBlockData("minecraft:trapped_chest[facing=" + chestFacing(p, x, y, z) + "]");
        if (chest instanceof org.bukkit.block.data.type.Chest half) half.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
        return chest;
    }

    private static BlockData head(Rng rng) {
        return Bukkit.createBlockData("minecraft:player_head[rotation=" + rng.nextInt(16) + "]");
    }

    private static String chestFacing(Placement p, int x, int y, int z) {
        String open = null;
        for (BlockFace face : SIDES) {
            BlockData front = p.at(x + face.getModX(), y, z + face.getModZ());
            if (front != null && front.getMaterial().isSolid()) continue;
            BlockData back = p.at(x - face.getModX(), y, z - face.getModZ());
            if (back != null && back.getMaterial().isSolid()) return face.name().toLowerCase();
            if (open == null) open = face.name().toLowerCase();
        }
        return open != null ? open : "north";
    }

    private void placeSpawn() {
        Placement entrance = byRoom[find(RoomType.ENTRANCE).id()];
        int cell = entrance.room().cells()[0];
        int bx = cellX(cell), bz = cellZ(cell);
        int side = Math.max(0, worldDoorSide(entrance.room()));
        int inX = SIDE_IN[side][0], inZ = SIDE_IN[side][1];
        int wallX = bx + SIDE_CENTRE[side][0], wallZ = bz + SIDE_CENTRE[side][1];

        int mx = wallX + inX * 4, mz = wallZ + inZ * 4;
        int my = standY(entrance, mx, mz);
        if (my == Integer.MIN_VALUE) my = 69;
        int sx = mx + inX * 2, sz = mz + inZ * 2;
        int sy = standY(entrance, sx, sz);
        if (sy == Integer.MIN_VALUE) {
            int[] spot = nearestStand(entrance, sx, sz);
            sx = spot[0];
            sy = spot[1];
            sz = spot[2];
        }

        float towardDoor = switch (side) {
            case 0 -> 180f;
            case 1 -> -90f;
            case 2 -> 0f;
            default -> 90f;
        };
        mortX = mx + 0.5;
        mortY = my;
        mortZ = mz + 0.5;
        mortYaw = towardDoor + 180f;
        spawnX = sx + 0.5;
        spawnY = sy;
        spawnZ = sz + 0.5;
        spawnYaw = towardDoor;
    }

    private static int[] nearestStand(Placement p, int x0, int z0) {
        for (int r = 1; r <= 10; r++) {
            for (int ox = -r; ox <= r; ox++) {
                for (int oz = -r; oz <= r; oz++) {
                    if (Math.max(Math.abs(ox), Math.abs(oz)) != r) continue;
                    int y = standY(p, x0 + ox, z0 + oz);
                    if (y != Integer.MIN_VALUE) return new int[]{x0 + ox, y, z0 + oz};
                }
            }
        }
        return new int[]{x0, 75, z0};
    }

    private static int standY(Placement p, int x, int z) {
        for (int y = 60; y < 110; y++) {
            BlockData below = p.at(x, y - 1, z);
            if (below == null || !below.getMaterial().isSolid()) continue;
            BlockData head = p.at(x, y + 1, z);
            if (p.at(x, y, z) == null && (head == null || !head.getMaterial().isSolid())) return y;
        }
        return Integer.MIN_VALUE;
    }
}
