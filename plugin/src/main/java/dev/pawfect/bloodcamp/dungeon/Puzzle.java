package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

abstract class Puzzle {
    enum State { WAITING, ACTIVE, SOLVED, FAILED }

    record Frame(int turns, int cornerX, int cornerZ) {
        static final Frame IDENTITY = new Frame(0, 0, 0);

        int[] toWorld(int rx, int rz) {
            return switch (turns) {
                case 0 -> new int[]{rx + cornerX, rz + cornerZ};
                case 1 -> new int[]{-rz + cornerX, rx + cornerZ};
                case 2 -> new int[]{-rx + cornerX, -rz + cornerZ};
                default -> new int[]{rz + cornerX, -rx + cornerZ};
            };
        }

        int[] toRoom(int x, int z) {
            return switch (turns) {
                case 0 -> new int[]{x - cornerX, z - cornerZ};
                case 1 -> new int[]{z - cornerZ, -x + cornerX};
                case 2 -> new int[]{-x + cornerX, -z + cornerZ};
                default -> new int[]{-z + cornerZ, x - cornerX};
            };
        }

        double[] rotate(double dx, double dz, int quarter) {
            return switch (Math.floorMod(quarter, 4)) {
                case 0 -> new double[]{dx, dz};
                case 1 -> new double[]{-dz, dx};
                case 2 -> new double[]{-dx, -dz};
                default -> new double[]{dz, -dx};
            };
        }

        double[] toWorld(double rx, double rz) {
            int bx = (int) Math.floor(rx), bz = (int) Math.floor(rz);
            int[] b = toWorld(bx, bz);
            double[] d = rotate(rx - bx - 0.5, rz - bz - 0.5, turns);
            return new double[]{b[0] + 0.5 + d[0], b[1] + 0.5 + d[1]};
        }

        double[] toRoom(double x, double z) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            int[] b = toRoom(bx, bz);
            double[] d = rotate(x - bx - 0.5, z - bz - 0.5, -turns);
            return new double[]{b[0] + 0.5 + d[0], b[1] + 0.5 + d[1]};
        }

        BlockFace face(BlockFace relative) {
            BlockFace f = relative;
            for (int i = 0; i < turns; i++) {
                f = switch (f) {
                    case NORTH -> BlockFace.EAST;
                    case EAST -> BlockFace.SOUTH;
                    case SOUTH -> BlockFace.WEST;
                    case WEST -> BlockFace.NORTH;
                    default -> f;
                };
            }
            return f;
        }

        float yaw(float relative) {
            return relative + 90f * turns;
        }

        BlockData rotated(BlockData data) {
            if (turns == 0) return data;
            BlockData copy = data.clone();
            copy.rotate(switch (turns) {
                case 1 -> StructureRotation.CLOCKWISE_90;
                case 2 -> StructureRotation.CLOCKWISE_180;
                default -> StructureRotation.COUNTERCLOCKWISE_90;
            });
            return copy;
        }
    }

    static final String GOOD_JOB = "§4G§co§6o§ed §aj§bo§db§5!";
    static final String YIKES = "§4Y§ci§6k§ee§as§b!";

    final Puzzles host;
    final World world;
    final Frame frame;
    final String room;
    final String name;
    final int roomIndex;
    final Random random = new Random();
    Consumer<Puzzle> listener;
    State state = State.WAITING;
    long startedAt;
    private final Map<Block, BlockData> original = new LinkedHashMap<>();
    private final List<Entity> spawned = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    Puzzle(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        this.host = host;
        this.world = world;
        this.frame = frame;
        this.room = room;
        this.name = name;
        this.roomIndex = roomIndex;
    }

    void prepare() {}

    void begin() {}

    void end() {}

    void tick() {}

    boolean ticksWhenSolved() {
        return false;
    }

    boolean click(Player player, Block block, boolean right) {
        return false;
    }

    boolean interact(Player player, Entity entity) {
        return false;
    }

    boolean hit(Player player, Entity entity, boolean melee) {
        return false;
    }

    boolean arrow(Player player, Block block) {
        return false;
    }

    final void start() {
        if (state != State.WAITING) return;
        state = State.ACTIVE;
        startedAt = System.nanoTime();
        begin();
        changed();
    }

    final void reset() {
        clear();
        prepare();
        changed();
    }

    final void clear() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        end();
        for (Entity e : spawned) if (e.isValid()) e.remove();
        spawned.clear();
        original.forEach((block, data) -> block.setBlockData(data, false));
        original.clear();
        state = State.WAITING;
    }

    final void solved(Player by, String what) {
        if (state != State.ACTIVE) return;
        state = State.SOLVED;
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        say("§a§lPUZZLE SOLVED! §r§e" + by.getName() + " §r§b" + what + " §r" + GOOD_JOB);
        say("§8" + name + " in " + Times.format(millis));
        host.onSolved.solved(by, name, millis);
        for (Player p : world.getPlayers()) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        changed();
    }

    final void failed(Player by, String what) {
        if (state != State.ACTIVE) return;
        state = State.FAILED;
        say("§c§lPUZZLE FAIL! §r§e" + by.getName() + " §r§b" + what + " §r" + YIKES);
        say("§8Resetting in 3s.");
        for (Player p : world.getPlayers()) p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
        changed();
        tasks.add(Bukkit.getScheduler().runTaskLater(host.plugin, this::reset, 60));
    }

    private void changed() {
        if (listener != null) listener.accept(this);
    }

    final void later(long ticks, Runnable run) {
        tasks.add(Bukkit.getScheduler().runTaskLater(host.plugin, () -> {
            if (state == State.ACTIVE) run.run();
        }, ticks));
    }

    final void after(long ticks, Runnable run) {
        tasks.add(Bukkit.getScheduler().runTaskLater(host.plugin, run, ticks));
    }
    final void say(String legacy) {
        Component line = LegacyComponentSerializer.legacySection().deserialize(legacy);
        for (Player p : world.getPlayers()) p.sendMessage(line);
    }

    final Block block(int rx, int y, int rz) {
        int[] w = frame.toWorld(rx, rz);
        return world.getBlockAt(w[0], y, w[1]);
    }

    final int[] rel(Block b) {
        int[] r = frame.toRoom(b.getX(), b.getZ());
        return new int[]{r[0], b.getY(), r[1]};
    }

    final double[] rel(Location l) {
        double[] r = frame.toRoom(l.getX(), l.getZ());
        return new double[]{r[0], l.getY(), r[1]};
    }

    final Location loc(double rx, double y, double rz, float relYaw) {
        double[] w = frame.toWorld(rx, rz);
        return new Location(world, w[0], y, w[1], frame.yaw(relYaw), 0f);
    }

    final boolean inside(Location l) {
        if (!l.getWorld().equals(world)) return false;
        double[] r = rel(l);
        return r[0] >= 0 && r[0] < 31 && r[2] >= 0 && r[2] < 31;
    }

    final boolean is(Block b, int rx, int y, int rz) {
        int[] r = rel(b);
        return r[0] == rx && r[1] == y && r[2] == rz;
    }

    final void set(Block block, BlockData data) {
        original.putIfAbsent(block, block.getBlockData());
        block.setBlockData(data, false);
    }

    final void set(int rx, int y, int rz, BlockData data) {
        set(block(rx, y, rz), data);
    }

    final void setUpdating(int rx, int y, int rz, BlockData data) {
        Block block = block(rx, y, rz);
        original.putIfAbsent(block, block.getBlockData());
        block.setBlockData(data, true);
    }

    final void set(int rx, int y, int rz, Material material) {
        set(block(rx, y, rz), material.createBlockData());
    }

    final BlockData state(String state) {
        return frame.rotated(Bukkit.createBlockData(state));
    }

    final <T extends Entity> T spawn(Location at, Class<T> type, Consumer<T> setup) {
        T entity = world.spawn(at, type, e -> {
            e.setPersistent(true);
            e.setSilent(true);
            e.setInvulnerable(true);
            e.getPersistentDataContainer().set(host.key, PersistentDataType.BYTE, (byte) 1);
            setup.accept(e);
        });
        spawned.add(entity);
        return entity;
    }

    final boolean owns(Entity entity) {
        return spawned.contains(entity);
    }

    final void forget(Entity entity) {
        spawned.remove(entity);
        if (entity.isValid()) entity.remove();
    }

    final List<Player> playersInside() {
        List<Player> out = new ArrayList<>();
        for (Player p : world.getPlayers()) if (inside(p.getLocation())) out.add(p);
        return out;
    }
}
