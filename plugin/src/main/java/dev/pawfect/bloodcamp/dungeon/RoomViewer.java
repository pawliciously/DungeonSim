package dev.pawfect.bloodcamp.dungeon;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class RoomViewer implements Listener {
    static final String PREFIX = "ds_view_";
    private static final long BUILD_NANOS_PER_TICK = 30_000_000L;
    static final Map<String, EquipmentSlot> SLOTS = Map.of(
        "mainhand", EquipmentSlot.HAND, "offhand", EquipmentSlot.OFF_HAND, "head", EquipmentSlot.HEAD,
        "chest", EquipmentSlot.CHEST, "legs", EquipmentSlot.LEGS, "feet", EquipmentSlot.FEET);

    record Recorded(String room, String shape, Path mobs, Path levers) {}

    private record Lever(int x, int y, int z, String name, int recordings, List<int[]> blocks, List<BlockData> was) {}

    private static final class View {
        final World world;
        final String room;
        final String shape;
        final boolean practice;
        final String name;
        final List<Lever> levers = new ArrayList<>();
        final boolean[] open;
        final boolean[] real;
        Location spawn;
        String secrets = "no secret data";

        View(World world, String room, String shape, boolean practice, String name, List<Lever> levers) {
            this.world = world;
            this.room = room;
            this.shape = shape;
            this.practice = practice;
            this.name = name;
            this.levers.addAll(levers);
            this.open = new boolean[levers.size()];
            this.real = new boolean[levers.size()];
        }
    }

    private final JavaPlugin plugin;
    private final RoomLibrary library;
    private final DungeonManager manager;
    private final Map<String, Recorded> recorded = new TreeMap<>();
    private final Map<String, View> views = new HashMap<>();
    private BukkitTask ticker;
    private int counter;
    private boolean building;

    RoomViewer(JavaPlugin plugin, RoomLibrary library, DungeonManager manager) {
        this.plugin = plugin;
        this.library = library;
        this.manager = manager;
    }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (View v : views.values()) {
                if (v.spawn == null) continue;
                for (Player p : v.world.getPlayers()) {
                    if (p.getLocation().getY() < -1) {
                        p.teleport(v.spawn);
                        p.setFallDistance(0);
                    }
                }
            }
        }, 1, 1);
    }

    void disable() {
        if (ticker != null) ticker.cancel();
        for (View v : List.copyOf(views.values())) {
            for (Player p : v.world.getPlayers()) manager.sendHome(p);
            remove(v);
        }
    }

    String reload() {
        recorded.clear();
        Path root = plugin.getDataFolder().toPath().resolve("records");
        Map<String, Path[]> found = new TreeMap<>();
        Map<String, String> shapes = new HashMap<>();
        for (String kind : List.of("mobs", "levers")) {
            Path dir = root.resolve(kind);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir, 2)) {
                for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                    String room = f.getFileName().toString().replace(".json", "");
                    shapes.put(room, f.getParent().getFileName().toString());
                    found.computeIfAbsent(room, r -> new Path[2])[kind.equals("mobs") ? 0 : 1] = f;
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't list " + dir + ": " + e.getMessage());
            }
        }
        found.forEach((room, paths) -> recorded.put(room, new Recorded(room, shapes.get(room), paths[0], paths[1])));
        long mobs = recorded.values().stream().filter(r -> r.mobs() != null).count();
        long levers = recorded.values().stream().filter(r -> r.levers() != null).count();
        return "recorded rooms " + recorded.size() + " (mobs " + mobs + ", levers " + levers + ")";
    }

    List<String> rooms() {
        return List.copyOf(recorded.keySet());
    }

    record Info(String name, String shape, int secrets, boolean practice) {}

    Info info(World world) {
        View v = views.get(world.getName());
        if (v == null) return null;
        RoomLibrary.RoomSecrets s = library.secrets(v.room);
        return new Info(v.name, v.shape, s == null ? 0 : s.count(), v.practice);
    }

    boolean isView(World world) {
        return views.containsKey(world.getName());
    }

    void list(Player player) {
        if (recorded.isEmpty()) {
            player.sendMessage(Component.text("No recordings on the server yet. Run tools/import-records.mjs, then /bc reload.", NamedTextColor.GRAY));
            return;
        }
        Component line = Component.text("Recorded rooms: ", NamedTextColor.GRAY);
        boolean first = true;
        for (Recorded r : recorded.values()) {
            if (!first) line = line.append(Component.text(", ", NamedTextColor.DARK_GRAY));
            first = false;
            String what = (r.mobs() != null ? "mobs" : "") + (r.mobs() != null && r.levers() != null ? " + " : "") + (r.levers() != null ? "levers" : "");
            line = line.append(Component.text(r.room(), NamedTextColor.WHITE)
                .clickEvent(ClickEvent.runCommand("/bc view " + r.room()))
                .hoverEvent(Component.text(r.shape() + ": " + what)));
        }
        player.sendMessage(line);
    }

    void view(Player player, String room) {
        Recorded rec = recorded.get(room);
        if (rec == null) {
            player.sendMessage(Component.text("No recordings for " + room + ". /bc view lists them.", NamedTextColor.RED));
            return;
        }
        RoomLibrary.Entry entry = library.pool(rec.shape()).stream().filter(e -> e.id().equals(room)).findFirst().orElse(null);
        if (entry == null) {
            player.sendMessage(Component.text("The server has no blocks for " + room + " in rooms/" + rec.shape() + ".", NamedTextColor.RED));
            return;
        }
        open(player, entry, rec.shape(), rec, false);
    }

    void practice(Player player, String pool, RoomLibrary.Entry entry) {
        open(player, entry, pool, recorded.get(entry.id()), true);
    }

    boolean isBuilding() {
        return building;
    }

    private void open(Player player, RoomLibrary.Entry entry, String shape, Recorded rec, boolean practice) {
        String room = entry.id();
        if (building) {
            player.sendMessage(Component.text("Another room is still building, try again in a second.", NamedTextColor.RED));
            return;
        }
        RoomData data;
        JsonObject mobs, levers;
        try {
            data = library.load(entry);
            mobs = practice || rec == null || rec.mobs() == null ? null : read(rec.mobs());
            levers = rec == null || rec.levers() == null ? null : read(rec.levers());
        } catch (IOException | RuntimeException e) {
            player.sendMessage(Component.text("Couldn't read " + room + ": " + e.getMessage(), NamedTextColor.RED));
            return;
        }
        World world;
        try {
            world = manager.createWorld(PREFIX + room.toLowerCase().replaceAll("[^a-z0-9]", "_") + "_" + (counter++));
            world.setDifficulty(Difficulty.NORMAL);
        } catch (RuntimeException e) {
            player.sendMessage(Component.text("Couldn't make a world: " + e.getMessage(), NamedTextColor.RED));
            return;
        }
        View view = new View(world, room, shape, practice, practice ? entry.name() : data.name, levers == null ? List.of() : levers(levers));
        views.put(world.getName(), view);
        building = true;

        List<int[]> at = new ArrayList<>();
        List<BlockData> blocks = new ArrayList<>();
        for (int dy = 0; dy < data.sy; dy++) {
            for (int z = 0; z < data.sz; z++) {
                for (int x = 0; x < data.sx; x++) {
                    int index = data.data[(dy * data.sz + z) * data.sx + x];
                    if (data.isAir(index)) continue;
                    at.add(new int[] {data.ox + x, data.oy + dy, data.oz + z});
                    blocks.add(data.state(index, 0));
                }
            }
        }
        int[] next = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long until = System.nanoTime() + BUILD_NANOS_PER_TICK;
            int i = next[0];
            while (i < at.size() && System.nanoTime() < until) {
                int stop = Math.min(at.size(), i + 256);
                for (; i < stop; i++) world.getBlockAt(at.get(i)[0], at.get(i)[1], at.get(i)[2]).setBlockData(blocks.get(i), false);
            }
            next[0] = i;
            if (player.isOnline()) player.sendActionBar(Component.text("Building " + data.name + "  " + (100L * i / Math.max(1, at.size())) + "%", NamedTextColor.GRAY));
            if (i < at.size()) return;
            task[0].cancel();
            building = false;
            for (RoomData.Head h : data.heads) h.profile().applyTo(world.getBlockAt(h.rx(), h.y(), h.rz()));
            view.secrets = placeSecrets(world, data.room);
            for (Lever l : view.levers) for (int[] b : l.blocks()) l.was().add(world.getBlockAt(b[0], b[1], b[2]).getBlockData());
            for (int n = 0; n < view.levers.size(); n++) {
                Lever l = view.levers.get(n);
                view.real[n] = placeLever(world.getBlockAt(l.x(), l.y(), l.z()));
            }
            int spawned = mobs == null ? 0 : spawnMobs(world, mobs);
            view.spawn = spawnPoint(world, data);
            if (practice) {
                Puzzle puzzle = manager.puzzles().add(world, Puzzle.Frame.IDENTITY, data.room, view.name, -1, p -> {
                    if (p.state == Puzzle.State.SOLVED) restartLater(view, p);
                });
                Location door = new Location(world, 15.5, 69, 2.5, 0f, 0f);
                if (puzzle != null && world.getBlockAt(15, 68, 2).isSolid() && world.getBlockAt(15, 69, 2).isPassable()) view.spawn = door;
            }
            if (player.isOnline()) enter(player, view, mobs, spawned);
        }, 1, 1);
    }

    private void restartLater(View view, Puzzle puzzle) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (views.get(view.world.getName()) != view || puzzle.state != Puzzle.State.SOLVED) return;
            puzzle.reset();
            for (Player p : view.world.getPlayers()) {
                p.teleport(view.spawn);
                p.sendMessage(Component.text("Puzzle reset, go again.", NamedTextColor.GRAY));
            }
        }, 100);
    }

    private void enter(Player player, View view, JsonObject mobs, int spawned) {
        manager.rememberMode(player);
        player.teleportAsync(view.spawn).thenAccept(ok -> {
            if (!ok) return;
            if (view.practice) {
                if (player.getGameMode() != GameMode.CREATIVE) player.setGameMode(GameMode.ADVENTURE);
                DungeonItems.giveKit(plugin, player);
                Speed.apply(player, Speed.get(player));
                player.sendMessage(Component.text("Practicing ", NamedTextColor.GRAY).append(Component.text(view.name, NamedTextColor.WHITE))
                    .append(Component.text(" (" + view.shape + "). Secrets: " + view.secrets + ".", NamedTextColor.GRAY)));
                if (!view.levers.isEmpty()) player.sendMessage(Component.text(view.levers.size() + " lever(s) work like on Hypixel.", NamedTextColor.DARK_GRAY));
                player.sendMessage(Component.text("/bc leave to go back.", NamedTextColor.DARK_GRAY));
                return;
            }
            player.setGameMode(GameMode.CREATIVE);
            player.setFlying(true);
            String mobInfo = mobs == null ? "no mobs recorded"
                : spawned + " mobs from " + mobs.get("sessions").getAsInt() + " run(s)" + (mobs.has("recordedAt") ? ", recorded " + mobs.get("recordedAt").getAsString().substring(0, 10) : "");
            player.sendMessage(Component.text(view.name + " (" + view.shape + "): " + mobInfo + ". Mobs that were hidden in walls glow.", NamedTextColor.GRAY));
            player.sendMessage(Component.text("Secrets: " + view.secrets + ".", NamedTextColor.GRAY));
            if (view.levers.isEmpty()) {
                player.sendMessage(Component.text("No levers recorded here.", NamedTextColor.DARK_GRAY));
            } else {
                player.sendMessage(Component.text(view.levers.size() + " lever(s). Flip one to open what it opened on Hypixel, again to close it (green sparkles mark any that had nowhere to stand):", NamedTextColor.GRAY));
                for (Lever l : view.levers) {
                    player.sendMessage(Component.text("  " + (l.name() == null ? "Lever" : l.name()) + " at " + l.x() + " " + l.y() + " " + l.z() + ": "
                        + l.blocks().size() + " blocks, " + l.recordings() + " recording(s)" + (l.recordings() < 2 ? " (one run, may include other stuff)" : ""), NamedTextColor.DARK_GRAY));
                }
            }
            player.sendMessage(Component.text("/bc view " + view.room + " to rebuild, /bc leave to go back.", NamedTextColor.DARK_GRAY));
        });
    }

    private static JsonObject read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private List<Lever> levers(JsonObject json) {
        List<Lever> out = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("levers")) {
            JsonObject l = e.getAsJsonObject();
            JsonArray at = l.getAsJsonArray("at");
            List<int[]> blocks = new ArrayList<>();
            for (JsonElement b : l.getAsJsonArray("blocks")) {
                JsonArray pos = b.getAsJsonObject().getAsJsonArray("pos");
                blocks.add(new int[] {pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt()});
            }
            String name = l.has("secretName") && !l.get("secretName").isJsonNull() ? l.get("secretName").getAsString() : null;
            out.add(new Lever(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt(), name, l.get("recordings").getAsInt(), blocks, new ArrayList<>()));
        }
        return out;
    }

    private int spawnMobs(World world, JsonObject json) {
        int count = 0;
        for (JsonElement e : json.getAsJsonArray("mobs")) {
            try {
                if (spawnMob(world, e.getAsJsonObject())) count++;
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Couldn't spawn a recorded mob: " + ex);
            }
        }
        return count;
    }

    private boolean spawnMob(World world, JsonObject mob) {
        String id = mob.get("type").getAsString();
        EntityType type = id.equals("minecraft:player") ? EntityType.MANNEQUIN : Registry.ENTITY_TYPE.get(NamespacedKey.fromString(id));
        if (type == null || type.getEntityClass() == null) return false;
        JsonArray pos = mob.getAsJsonArray("pos");
        float yaw = mob.has("yaw") ? mob.get("yaw").getAsFloat() : 0f;
        Location at = new Location(world, pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble(), yaw, 0);
        boolean hidden = mob.has("invisible") && mob.get("invisible").getAsBoolean();
        String name = mob.has("name") && !mob.get("name").isJsonNull() ? mob.get("name").getAsString() : null;
        world.spawn(at, type.getEntityClass(), entity -> setUp(entity, mob, name, hidden));
        return true;
    }

    private void setUp(Entity entity, JsonObject mob, String name, boolean hidden) {
        entity.setGravity(false);
        entity.setSilent(true);
        entity.setInvulnerable(true);
        entity.setPersistent(true);
        if (hidden) entity.setGlowing(true);
        if (name != null) {
            entity.customName(LegacyComponentSerializer.legacySection().deserialize((hidden ? "§8[in wall] " : "") + name));
            entity.setCustomNameVisible(true);
        }
        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setRemoveWhenFarAway(false);
            living.setCollidable(false);
            if (mob.has("equipment") && living.getEquipment() != null) {
                for (var slot : mob.getAsJsonObject("equipment").entrySet()) {
                    EquipmentSlot s = SLOTS.get(slot.getKey());
                    ItemStack item = item(slot.getValue().getAsString());
                    if (s != null && item != null) {
                        living.getEquipment().setItem(s, item);
                        living.getEquipment().setDropChance(s, 0f);
                    }
                }
            }
        }
        if (entity instanceof Zombie zombie) zombie.setShouldBurnInDay(false);
        if (entity instanceof AbstractSkeleton skeleton) skeleton.setShouldBurnInDay(false);
        if (entity instanceof Bat bat) bat.setAwake(true);
        if (entity instanceof Mannequin mannequin) {
            mannequin.setImmovable(true);
            mannequin.setDescription(null);
            if (mob.has("skin")) {
                mannequin.setProfile(ResolvableProfile.resolvableProfile()
                    .addProperty(new ProfileProperty("textures", mob.get("skin").getAsString())).build());
            }
        }
    }

    static ItemStack item(String snbt) {
        try {
            CompoundTag tag = TagParser.parseCompoundFully(snbt);
            var ops = RegistryOps.create(NbtOps.INSTANCE, MinecraftServer.getServer().registryAccess());
            return net.minecraft.world.item.ItemStack.CODEC.parse(ops, tag).result().map(CraftItemStack::asBukkitCopy).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String placeSecrets(World world, String room) {
        RoomLibrary.RoomSecrets secrets = library.secrets(room);
        if (secrets == null) return "no secret data for this room";
        int chests = 0, essence = 0, keys = 0, items = 0, bats = 0;
        for (RoomLibrary.Secret s : secrets.secrets()) {
            Block block = world.getBlockAt(s.x(), s.y(), s.z());
            switch (s.category()) {
                case "chest" -> {
                    if (block.isPassable()) block.setBlockData(Bukkit.createBlockData("minecraft:chest[facing=" + chestFacing(block) + "]"), false);
                    chests++;
                }
                case "wither" -> {
                    head(block, HeadProfile.WITHER_ESSENCE);
                    essence++;
                }
                case "key" -> {
                    if (s.name().contains("Place")) continue;
                    head(block, HeadProfile.REDSTONE_KEY);
                    keys++;
                }
                case "item" -> {
                    world.spawn(block.getLocation().add(0.5, 0.4, 0.5), ItemDisplay.class, d -> {
                        d.setItemStack(new ItemStack(Material.ENDER_PEARL));
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setGlowing(true);
                        d.setPersistent(true);
                    });
                    items++;
                }
                case "bat" -> {
                    world.spawn(block.getLocation().add(0.5, 0.2, 0.5), Bat.class, b -> {
                        b.setAI(false);
                        b.setAwake(true);
                        b.setGravity(false);
                        b.setSilent(true);
                        b.setInvulnerable(true);
                        b.setPersistent(true);
                        b.setGlowing(true);
                        b.customName(Component.text("Secret Bat", NamedTextColor.GRAY));
                        b.setCustomNameVisible(true);
                    });
                    bats++;
                }
                default -> { }
            }
        }
        return secrets.count() + " counted (" + chests + " chests, " + essence + " essence, " + items + " items, " + bats + " bats"
            + (keys > 0 ? ", " + keys + " redstone key spot(s)" : "") + ")";
    }

    private static void head(Block block, HeadProfile profile) {
        if (!block.isPassable() && block.getType() != Material.PLAYER_HEAD) return;
        if (block.getType() != Material.PLAYER_HEAD) block.setBlockData(Bukkit.createBlockData("minecraft:player_head[rotation=" + (int) (Math.random() * 16) + "]"), false);
        profile.applyTo(block);
    }

    private static String chestFacing(Block block) {
        String open = null;
        for (BlockFace face : WALLS) {
            if (block.getRelative(face).isSolid()) continue;
            if (block.getRelative(face.getOppositeFace()).isSolid()) return face.name().toLowerCase();
            if (open == null) open = face.name().toLowerCase();
        }
        return open != null ? open : "north";
    }

    private static Location spawnPoint(World world, RoomData data) {
        int x = data.ox + data.sx / 2, z = data.oz + data.sz / 2;
        for (int y = Math.max(data.oy + 1, 60); y < data.oy + data.sy; y++) {
            if (world.getBlockAt(x, y - 1, z).isSolid() && world.getBlockAt(x, y, z).isPassable() && world.getBlockAt(x, y + 1, z).isPassable()) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return new Location(world, x + 0.5, data.oy + data.sy + 1, z + 0.5);
    }

    private static final BlockFace[] WALLS = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

    private static boolean placeLever(Block block) {
        if (block.getType() == Material.LEVER) return true;
        if (!block.isPassable()) return false;
        Switch lever = (Switch) Material.LEVER.createBlockData();
        if (block.getRelative(BlockFace.DOWN).isSolid()) {
            lever.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
        } else {
            BlockFace wall = null;
            for (BlockFace f : WALLS) if (block.getRelative(f).getType().isOccluding()) { wall = f; break; }
            if (wall == null) for (BlockFace f : WALLS) if (block.getRelative(f).isSolid()) { wall = f; break; }
            if (wall != null) {
                lever.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                lever.setFacing(wall.getOppositeFace());
            } else {
                lever.setAttachedFace(FaceAttachable.AttachedFace.CEILING);
            }
        }
        block.setBlockData(lever, false);
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        View view = views.get(block.getWorld().getName());
        if (view == null) return;
        for (int i = 0; i < view.levers.size(); i++) {
            Lever l = view.levers.get(i);
            if (Math.abs(l.x() - block.getX()) > 1 || Math.abs(l.y() - block.getY()) > 1 || Math.abs(l.z() - block.getZ()) > 1) continue;
            view.open[i] = !view.open[i];
            Block leverBlock = view.world.getBlockAt(l.x(), l.y(), l.z());
            if (leverBlock.getBlockData() instanceof Switch lever) {
                event.setCancelled(true);
                lever.setPowered(view.open[i]);
                leverBlock.setBlockData(lever, false);
                view.world.playSound(leverBlock.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_LEVER_CLICK, 0.3f, view.open[i] ? 0.6f : 0.5f);
            }
            for (int b = 0; b < l.blocks().size(); b++) {
                int[] p = l.blocks().get(b);
                view.world.getBlockAt(p[0], p[1], p[2]).setBlockData(view.open[i] ? org.bukkit.Material.AIR.createBlockData() : l.was().get(b), false);
            }
            event.getPlayer().sendActionBar(Component.text((l.name() == null ? "Lever" : l.name()) + (view.open[i] ? ": opened " : ": closed ")
                + l.blocks().size() + " blocks", view.open[i] ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            return;
        }
    }

    private void tick() {
        for (View v : views.values()) {
            for (int i = 0; i < v.levers.size(); i++) {
                Lever l = v.levers.get(i);
                if (!v.real[i]) v.world.spawnParticle(Particle.HAPPY_VILLAGER, l.x() + 0.5, l.y() + 0.5, l.z() + 0.5, 4, 0.25, 0.25, 0.25, 0);
            }
        }
    }

    @EventHandler
    public void onCombust(EntityCombustEvent event) {
        if (isView(event.getEntity().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onPhysics(org.bukkit.event.block.BlockPhysicsEvent event) {
        if (isView(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onFlow(org.bukkit.event.block.BlockFromToEvent event) {
        if (isView(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onLeavesDecay(org.bukkit.event.block.LeavesDecayEvent event) {
        if (isView(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onItemSpawn(org.bukkit.event.entity.ItemSpawnEvent event) {
        if (isView(event.getEntity().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        View from = views.get(event.getFrom().getName());
        if (from == null) return;
        if (!manager.ours(event.getPlayer().getWorld())) {
            DungeonSecrets.stripItems(plugin, event.getPlayer(), true);
            Speed.reset(event.getPlayer());
            manager.restoreMode(event.getPlayer());
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (views.get(from.world.getName()) == from && from.world.getPlayers().isEmpty()) remove(from);
        }, 20);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (isView(event.getPlayer().getWorld())) manager.sendHome(event.getPlayer());
    }

    private void remove(View v) {
        views.remove(v.world.getName());
        manager.puzzles().remove(v.world);
        manager.unloadAndDelete(v.world);
    }
}
