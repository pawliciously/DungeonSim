package dev.pawfect.bloodcamp.dungeon;

import dev.pawfect.bloodcamp.gen.Layout;
import dev.pawfect.bloodcamp.gen.Layout.DoorType;
import dev.pawfect.bloodcamp.gen.LayoutGenerator;
import dev.pawfect.bloodcamp.gen.Seed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class DungeonManager implements Listener {
    private static final String PREFIX = "ds_";
    private static final long BUILD_NANOS_PER_TICK = 30_000_000L;

    private final JavaPlugin plugin;
    private final RoomLibrary library;
    private final Times times;
    private final NamespacedKey previousMode;
    private final Map<String, Dungeon> dungeons = new HashMap<>();
    private final HypixelLook look = new HypixelLook();
    private final BrandLook brand = new BrandLook();
    private final NamespacedKey parkourBest;
    private final RoomViewer viewer;
    private final Lobby lobby;
    private final MapMenu menu;
    private final RoomMenu rooms;
    private final Puzzles puzzles;
    private final Loadout loadout;
    private Seed pendingSeed;
    private int pendingNumber = -1;
    private static NamespacedKey classKey;
    private BukkitTask ticker;
    private long ticks;
    private boolean building;
    private int counter;

    private static final class Dungeon {
        final World world;
        final Seed seed;
        final DungeonPlan plan;
        final boolean[] open;
        final DungeonSecrets secrets;
        final DungeonMap map;
        final String serverName;
        int number;
        int buildPercent;
        boolean built;
        long startedAt = -1;
        long finishedMillis = -1;
        boolean finished;
        Player actor;
        int witherKeys, witherDoorsOpened;
        boolean bloodKey, bloodDoorOpened;
        final Map<java.util.UUID, Integer> runSpeed = new HashMap<>();

        Dungeon(JavaPlugin plugin, World world, Seed seed, DungeonPlan plan, RoomLibrary.Mimic mimic) {
            this.world = world;
            this.seed = seed;
            this.plan = plan;
            this.secrets = new DungeonSecrets(plugin, world, plan, mimic);
            this.open = new boolean[plan.doors.size()];
            for (int i = 0; i < open.length; i++) open[i] = plan.doors.get(i).type() == DoorType.NORMAL;
            this.map = new DungeonMap(plan, secrets, open);
            String code = seed.toString();
            this.serverName = String.format("mini%02X%s", Math.floorMod(seed.value(), 256), code.substring(code.length() - 2));
        }

        long elapsed() {
            return startedAt < 0 ? 0 : (System.nanoTime() - startedAt) / 1_000_000;
        }

        Location spawn() {
            Location at = new Location(world, plan.spawnX, plan.spawnY, plan.spawnZ);
            at.setYaw(plan.spawnYaw);
            return at;
        }

        boolean running() {
            return startedAt >= 0 && !finished;
        }
    }

    public DungeonManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.library = new RoomLibrary(plugin.getDataFolder().toPath(), plugin.getLogger());
        this.times = new Times(plugin.getDataFolder().toPath().resolve("clears.yml").toFile(), plugin.getLogger());
        Speed.init(plugin);
        this.previousMode = new NamespacedKey(plugin, "previous_gamemode");
        this.parkourBest = new NamespacedKey(plugin, "parkour_best");
        classKey = new NamespacedKey(plugin, "dungeon_class");
        this.puzzles = new Puzzles(plugin);
        this.loadout = new Loadout(plugin, p -> {
            if (ours(p.getWorld()) && !inRun(p)) DungeonItems.giveKit(plugin, p);
        });
        this.viewer = new RoomViewer(plugin, library, this);
        this.menu = new MapMenu(plugin, this, times);
        this.rooms = new RoomMenu(plugin, library, viewer);
        this.lobby = new Lobby(plugin, menu::open, rooms::open, times);
    }

    public static final List<String> CLASSES = List.of("Archer", "Berserk", "Healer", "Mage", "Tank");

    static String dungeonClass(Player player) {
        String c = classKey == null ? null : player.getPersistentDataContainer().get(classKey, PersistentDataType.STRING);
        return c == null ? "Archer" : c;
    }

    public void setDungeonClass(Player player, String name) {
        player.getPersistentDataContainer().set(classKey, PersistentDataType.STRING, name);
    }

    public void locraw(Player player) {
        Dungeon d = dungeonOf(player.getWorld());
        String json = d == null ? "{\"server\":\"bloodcamp\",\"gametype\":\"MAIN\",\"lobbyname\":\"bloodcamp\"}"
            : "{\"server\":\"" + d.serverName + "\",\"gametype\":\"SKYBLOCK\",\"mode\":\"dungeon\",\"map\":\"Dungeon\"}";
        player.sendMessage(Component.text(json));
    }

    public void enable() {
        library.reload();
        plugin.getLogger().info("Rooms: " + library.summary() + ", " + viewer.reload());
        deleteLeftoverWorlds();
        lobby.enable();
        menu.enable();
        rooms.enable();
        puzzles.enable();
        loadout.enable();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        viewer.enable();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    public void disable() {
        if (ticker != null) ticker.cancel();
        puzzles.disable();
        viewer.disable();
        menu.disable();
        for (Dungeon d : List.copyOf(dungeons.values())) {
            for (Player p : d.world.getPlayers()) sendHome(p);
            remove(d, false);
        }
    }

    Puzzles puzzles() {
        return puzzles;
    }

    public String reload() {
        library.reload();
        return library.summary() + ", " + viewer.reload();
    }

    public void generate(CommandSender sender, Seed seed) {
        if (building) {
            sender.sendMessage(Component.text("Another map is still building, try again in a few seconds.", NamedTextColor.RED));
            return;
        }
        int number = freeNumber();
        if (number < 0) {
            sender.sendMessage(Component.text("All " + MapMenu.MAX_MAPS + " map slots are taken. One frees up when everyone leaves it.", NamedTextColor.RED));
            return;
        }
        building = true;
        pendingSeed = seed;
        pendingNumber = number;
        sender.sendMessage(Component.text("Building ", NamedTextColor.GRAY).append(code(seed)).append(Component.text("...", NamedTextColor.GRAY)));
        CompletableFuture.supplyAsync(() -> {
            try {
                return new DungeonPlan(LayoutGenerator.generate(seed), library);
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't read a room file: " + e.getMessage(), e);
            }
        }).whenComplete((plan, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                building = false;
                pendingSeed = null;
                pendingNumber = -1;
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                sender.sendMessage(Component.text("Couldn't build " + seed.code() + ": " + cause.getMessage(), NamedTextColor.RED));
                plugin.getLogger().warning("Build of " + seed + " failed: " + cause);
                return;
            }
            build(sender, seed, plan);
        }));
    }

    private void build(CommandSender sender, Seed seed, DungeonPlan plan) {
        Player player = sender instanceof Player p ? p : null;
        World world;
        try {
            world = createWorld(PREFIX + (seed.code() + "_" + seed.floor().name()).toLowerCase() + "_" + (counter++));
        } catch (RuntimeException e) {
            building = false;
            pendingSeed = null;
            pendingNumber = -1;
            sender.sendMessage(Component.text("Couldn't make a world: " + e.getMessage(), NamedTextColor.RED));
            return;
        }
        world.setDifficulty(Difficulty.NORMAL);
        Dungeon dungeon = new Dungeon(plugin, world, seed, plan, library.mimic());
        dungeon.number = pendingNumber;
        pendingSeed = null;
        pendingNumber = -1;
        dungeon.map.onOpen = room -> keyPickup(dungeon, room);
        dungeons.put(world.getName(), dungeon);

        DungeonPlan.Writes writes = plan.writes;
        int[] next = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long until = System.nanoTime() + BUILD_NANOS_PER_TICK;
            int i = next[0];
            while (i < writes.size && System.nanoTime() < until) {
                int stop = Math.min(writes.size, i + 256);
                for (; i < stop; i++) {
                    world.getBlockAt(writes.xyz[i * 3], writes.xyz[i * 3 + 1], writes.xyz[i * 3 + 2]).setBlockData(writes.blocks[i], false);
                }
            }
            next[0] = i;
            dungeon.buildPercent = (int) (100L * i / Math.max(1, writes.size));
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.text("Building " + seed.code() + "  " + (100L * i / Math.max(1, writes.size)) + "%", NamedTextColor.GRAY));
            }
            if (i >= writes.size) {
                task[0].cancel();
                building = false;
                dungeon.built = true;
                dungeon.secrets.start();
                addPuzzles(dungeon);
                spawnMort(dungeon);
                plugin.getLogger().info("Built " + world.getName() + " (" + writes.size + " blocks), spawn " + dungeon.spawn().toVector() + ". " + summary(plan));
                if (player == null) {
                    sender.sendMessage(Component.text("Built " + world.getName() + ", " + writes.size + " blocks, spawn " + dungeon.spawn().toVector() + ". " + summary(plan)));
                } else if (player.isOnline()) {
                    enter(player, dungeon);
                }
            }
        }, 1, 1);
    }

    private void addPuzzles(Dungeon d) {
        for (DungeonPlan.Placement p : d.plan.placements) {
            if (p.room().type() != Layout.RoomType.PUZZLE) continue;
            int room = p.room().id();
            puzzles.add(d.world, new Puzzle.Frame(p.turns(), p.cornerX(), p.cornerZ()), p.data().room, p.data().name.replace("*", ""), room, puzzle -> {
                if (puzzle.state == Puzzle.State.SOLVED) d.map.solvePuzzle(room);
            });
        }
    }

    World createWorld(String name) {
        World world = new WorldCreator(name)
            .generator(new VoidGenerator())
            .environment(World.Environment.NORMAL)
            .generateStructures(false)
            .createWorld();
        if (world == null) throw new IllegalStateException("createWorld returned null");
        world.setAutoSave(false);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.FALL_DAMAGE, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        world.setTime(6000);
        return world;
    }

    private void enter(Player player, Dungeon dungeon) {
        rememberMode(player);
        player.teleportAsync(dungeon.spawn()).thenAccept(ok -> {
            if (!ok) return;
            if (player.getGameMode() != GameMode.CREATIVE) player.setGameMode(GameMode.ADVENTURE);
            DungeonItems.giveKit(plugin, player);
            Speed.apply(player, Speed.get(player));
            if (dungeon.startedAt >= 0) giveMap(dungeon, player);
            look.show(player, state(dungeon));
            for (Player p : dungeon.world.getPlayers()) p.sendMessage(HypixelChat.entered(player.getName(), dungeon.seed.floor()));
            player.sendMessage(Component.text("Map ", NamedTextColor.GRAY).append(code(dungeon.seed))
                .append(Component.text(" ready. Right-click Mort to start, right-click doors to open them. Superboom breaks cracked walls, AOTV: right click 12 blocks, sneak right click etherwarps up to 61.", NamedTextColor.GRAY)));
            player.sendMessage(Component.text(summary(dungeon.plan), NamedTextColor.DARK_GRAY));
        });
    }

    private static String summary(DungeonPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (DungeonPlan.Placement p : plan.placements) {
            Layout.RoomType type = p.room().type();
            if (type == Layout.RoomType.NORMAL || type == Layout.RoomType.ENTRANCE) continue;
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(p.data().name);
        }
        return "Specials: " + sb;
    }

    public boolean join(Player player, Player target) {
        Dungeon dungeon = dungeonOf(target.getWorld());
        if (dungeon == null) return false;
        String problem = joinMap(player, dungeon.world.getName());
        if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
        return true;
    }

    String joinMap(Player player, String worldName) {
        Dungeon d = dungeons.get(worldName);
        if (d == null) return "That map just closed.";
        if (!d.built) return "That map is still building.";
        if (player.getWorld().equals(d.world)) return "You're already in that map.";
        if (d.world.getPlayers().size() >= MapMenu.MAX_PLAYERS) return "That map is full (" + MapMenu.MAX_PLAYERS + " players).";
        enter(player, d);
        return null;
    }

    private void giveMap(Dungeon d, Player player) {
        for (org.bukkit.inventory.ItemStack stack : player.getInventory().getContents()) {
            if (DungeonSecrets.tagOf(plugin, stack) == 4) return;
        }
        Integer slot = Loadout.layout(plugin, player).get((byte) 4);
        if (slot != null) DungeonItems.place(player.getInventory(), slot, d.map.item(plugin, d.world));
    }

    public String giveMap(Player player) {
        Dungeon d = dungeonOf(player.getWorld());
        if (d == null) return "You're not in a map.";
        giveMap(d, player);
        return null;
    }

    boolean isBuilding() {
        return building;
    }

    List<MapMenu.MapInfo> maps() {
        List<MapMenu.MapInfo> out = new java.util.ArrayList<>();
        if (pendingSeed != null) {
            out.add(new MapMenu.MapInfo(pendingNumber, null, pendingSeed, List.of(), MapMenu.State.BUILDING, 0, 0, 0, 0));
        }
        for (Dungeon d : dungeons.values()) {
            MapMenu.State state = !d.built ? MapMenu.State.BUILDING : d.finished ? MapMenu.State.FINISHED
                : d.running() ? MapMenu.State.RUNNING : MapMenu.State.WAITING;
            long millis = d.finished ? d.finishedMillis : d.elapsed();
            List<String> names = d.world.getPlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            out.add(new MapMenu.MapInfo(d.number, d.world.getName(), d.seed, names, state, d.buildPercent, millis,
                d.built ? d.secrets.totalFound() : 0, d.built ? d.secrets.totalAll() : 0));
        }
        return out;
    }

    private int freeNumber() {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (Dungeon d : dungeons.values()) used.add(d.number);
        if (pendingNumber > 0) used.add(pendingNumber);
        for (int n = 1; n <= MapMenu.MAX_MAPS; n++) if (!used.contains(n)) return n;
        return -1;
    }

    public void openMenu(Player player) {
        menu.open(player);
    }

    void openLoadout(Player player) {
        loadout.open(player);
    }

    public void openRooms(Player player) {
        rooms.open(player);
    }

    public boolean cancelSeed(Player player) {
        return menu.cancelTyping(player);
    }

    public boolean leave(Player player) {
        if (!ours(player.getWorld())) return false;
        sendHome(player);
        return true;
    }

    public boolean reset(Player player) {
        Dungeon d = dungeonOf(player.getWorld());
        if (d == null) return false;
        for (int i = 0; i < d.open.length; i++) {
            DungeonPlan.Door door = d.plan.doors.get(i);
            if (door.type() == DoorType.NORMAL) continue;
            setMiddle(d, door, DungeonPlan.closedBlock(door.type()));
            d.open[i] = false;
        }
        d.startedAt = -1;
        d.finishedMillis = -1;
        d.finished = false;
        d.secrets.reset();
        d.map.reset();
        puzzles.reset(d.world);
        d.witherKeys = 0;
        d.witherDoorsOpened = 0;
        d.bloodKey = false;
        d.bloodDoorOpened = false;
        d.actor = null;
        d.runSpeed.clear();
        for (Player p : d.world.getPlayers()) {
            p.teleportAsync(d.spawn());
            DungeonItems.giveKit(plugin, p);
        }
        return true;
    }

    public void showTimes(Player player, Seed seed) {
        List<Times.Clear> top = times.top(Times.onSeed(seed.code(), seed.floor().name()), 10);
        if (top.isEmpty()) {
            player.sendMessage(Component.text("No clears on " + seed.code() + " yet.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("Fastest on " + seed.code() + ":", NamedTextColor.GRAY));
        for (int i = 0; i < top.size(); i++) {
            Times.Clear c = top.get(i);
            player.sendMessage(Component.text((i + 1) + ". " + c.name() + "  " + Times.format(c.millis()), i == 0 ? NamedTextColor.GOLD : NamedTextColor.WHITE)
                .append(Component.text("  \u2726" + c.speed() + (c.party() > 1 ? "  " + c.party() + " man" : ""), NamedTextColor.DARK_GRAY)));
        }
    }

    public Seed seedAt(World world) {
        Dungeon d = dungeonOf(world);
        return d == null ? null : d.seed;
    }

    boolean inRun(Player player) {
        Dungeon d = dungeonOf(player.getWorld());
        return d != null && d.running();
    }

    void speedChanged(Player player) {
        if (dungeonOf(player.getWorld()) != null) Speed.apply(player, Speed.get(player));
    }

    private Dungeon dungeonOf(World world) {
        return dungeons.get(world.getName());
    }

    private void tick() {
        ticks++;
        if (ticks % 10 == 0) brandTick();
        for (Dungeon d : dungeons.values()) {
            for (Player p : d.world.getPlayers()) {
                if (p.getLocation().getY() < -1) {
                    p.teleport(d.spawn());
                    p.setFallDistance(0);
                }
            }
            if (d.running()) {
                long millis = d.elapsed();
                for (Player p : d.world.getPlayers()) {
                    Location at = p.getLocation();
                    int x = at.getBlockX(), z = at.getBlockZ();
                    int room = roomAt(d, x, z);
                    if (room >= 0 && !d.map.isOpened(room)) d.actor = p;
                    if (room >= 0) d.map.enter(room);
                    if (x >= d.plan.bloodMinX && x <= d.plan.bloodMinX + 30 && z >= d.plan.bloodMinZ && z <= d.plan.bloodMinZ + 30) {
                        finish(d, p, millis);
                        break;
                    }
                }
            }
            if (ticks % 5 == 0) d.secrets.restoreBroken(System.currentTimeMillis());
            if (ticks % 10 == 0) {
                for (Player p : d.world.getPlayers()) {
                    p.sendActionBar(actionBar(d, p));
                    DungeonItems.regenCharges(plugin, p);
                }
            }
            if (ticks % 20 == 0) {
                d.secrets.spawnNearbyBats(pi -> d.map.isOpened(d.plan.placements.get(pi).room().id()));
                HypixelLook.RunState state = state(d);
                for (Player p : d.world.getPlayers()) look.update(p, state);
            }
        }
    }

    private void brandTick() {
        String daily = Seed.today().code();
        int online = Bukkit.getOnlinePlayers().size();
        List<Times.Clear> board = null;
        net.kyori.adventure.text.Component header = net.kyori.adventure.text.Component.text()
            .append(net.kyori.adventure.text.Component.newline())
            .append(net.kyori.adventure.text.Component.text("BLOODCAMP", BrandLook.BRAND, net.kyori.adventure.text.format.TextDecoration.BOLD))
            .append(net.kyori.adventure.text.Component.newline())
            .append(net.kyori.adventure.text.Component.text("stop being ass. go practice.", BrandLook.LABEL))
            .append(net.kyori.adventure.text.Component.newline()).build();
        net.kyori.adventure.text.Component footer = net.kyori.adventure.text.Component.text()
            .append(net.kyori.adventure.text.Component.newline())
            .append(BrandLook.pair("Online", String.valueOf(online)))
            .append(net.kyori.adventure.text.Component.text("     "))
            .append(BrandLook.pair("Daily seed", daily))
            .append(net.kyori.adventure.text.Component.newline())
            .append(net.kyori.adventure.text.Component.text("bloodcamp.net", BrandLook.GOLD))
            .append(net.kyori.adventure.text.Component.newline()).build();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (look.showing(p)) {
                brand.forget(p);
                continue;
            }
            List<net.kyori.adventure.text.Component> lines = new java.util.ArrayList<>();
            lines.add(net.kyori.adventure.text.Component.text(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy")), BrandLook.DIM));
            lines.add(net.kyori.adventure.text.Component.empty());
            RoomViewer.Info room = viewer.info(p.getWorld());
            if (room != null) {
                lines.add(net.kyori.adventure.text.Component.text(room.practice() ? "Practicing" : "Viewing", BrandLook.LABEL));
                lines.add(net.kyori.adventure.text.Component.text(room.name(), BrandLook.VALUE, net.kyori.adventure.text.format.TextDecoration.BOLD));
                lines.add(net.kyori.adventure.text.Component.text(room.shape(), BrandLook.DIM));
                lines.add(net.kyori.adventure.text.Component.empty());
                if (room.secrets() > 0) lines.add(BrandLook.pair("Secrets", String.valueOf(room.secrets())));
                for (Puzzle puzzle : puzzles.in(p.getWorld())) {
                    String status = switch (puzzle.state) {
                        case WAITING -> "Walk in to start";
                        case ACTIVE -> Times.format((System.nanoTime() - puzzle.startedAt) / 1_000_000);
                        case SOLVED -> "Solved";
                        case FAILED -> "Failed";
                    };
                    lines.add(BrandLook.pair("Puzzle", status));
                }
                if (lines.getLast() != net.kyori.adventure.text.Component.empty()) lines.add(net.kyori.adventure.text.Component.empty());
                lines.add(BrandLook.pair("Speed", "✦" + Speed.get(p)));
            } else {
                if (board == null) board = times.top(Times.onFloor(Seed.FLOOR.name()), Integer.MAX_VALUE);
                int rank = -1;
                for (int i = 0; i < board.size(); i++) if (board.get(i).player().equals(p.getUniqueId())) rank = i + 1;
                lines.add(BrandLook.pair("Online", String.valueOf(online)));
                lines.add(BrandLook.pair("Maps", dungeons.size() + "/" + MapMenu.MAX_MAPS));
                lines.add(net.kyori.adventure.text.Component.empty());
                lines.add(BrandLook.pair("Daily", daily));
                lines.add(BrandLook.pair("Best", rank > 0 ? Times.format(board.get(rank - 1).millis()) : "none yet"));
                if (rank > 0) lines.add(BrandLook.pair("Rank", "#" + rank + " of " + board.size()));
                lines.add(net.kyori.adventure.text.Component.empty());
                lines.add(BrandLook.pair("Speed", "✦" + Speed.get(p)));
                Long parkour = p.getPersistentDataContainer().get(parkourBest, PersistentDataType.LONG);
                lines.add(BrandLook.pair("Parkour", parkour == null ? "none yet" : Times.format(parkour)));
            }
            lines.add(net.kyori.adventure.text.Component.empty());
            lines.add(net.kyori.adventure.text.Component.text("bloodcamp.net", BrandLook.GOLD));
            brand.show(p, lines, header, footer);
        }
    }

    private static int roomAt(Dungeon d, int x, int z) {
        int rx = x - DungeonPlan.GRID, rz = z - DungeonPlan.GRID;
        if (rx < 0 || rz < 0) return -1;
        int cx = rx / DungeonPlan.CELL, cz = rz / DungeonPlan.CELL;
        if (cx >= d.plan.layout.width || cz >= d.plan.layout.height) return -1;
        if (rx % DungeonPlan.CELL == DungeonPlan.CELL - 1 || rz % DungeonPlan.CELL == DungeonPlan.CELL - 1) return -1;
        return d.plan.layout.roomOf[cz * d.plan.layout.width + cx];
    }

    private static Component actionBar(Dungeon d, Player p) {
        String bar = "§c1,000/1,000     §a500     §b500/500 §3400     §4100/100";
        int room = d.plan.placementAt(p.getLocation().getBlockX(), p.getLocation().getBlockZ());
        if (room >= 0 && d.secrets.total(room) > 0) {
            int found = d.secrets.found(room), total = d.secrets.total(room);
            String color = found >= total ? "§a" : found > 0 ? "§e" : "§7";
            bar += "          " + color + found + "/" + total + " Secrets";
        }
        return Component.text(bar);
    }

    private HypixelLook.RunState state(Dungeon d) {
        List<String> puzzles = new java.util.ArrayList<>();
        for (DungeonPlan.Placement p : d.plan.placements) {
            if (p.room().type() != Layout.RoomType.PUZZLE) continue;
            Puzzle puzzle = this.puzzles.of(d.world, p.room().id());
            String mark = puzzle == null || puzzle.state == Puzzle.State.WAITING || puzzle.state == Puzzle.State.ACTIVE ? "§6§l✦"
                : puzzle.state == Puzzle.State.SOLVED ? "§a§l✔" : "§c§l✖";
            puzzles.add((d.map.isOpened(p.room().id()) ? p.data().name.replace("*", "") : "???") + ": §7[" + mark + "§7]");
        }
        String floor = d.seed.floor().name();
        int clearedPct = Math.min(100, 100 * d.map.clearedCount() / Math.max(1, d.map.clearable()));
        return new HypixelLook.RunState(floor, floor.startsWith("M"), floor.charAt(floor.length() - 1) - '0', d.serverName, d.elapsed(),
            d.startedAt >= 0, d.secrets.totalFound(), d.secrets.totalAll(), d.map.openedCount(), d.map.clearedCount(),
            clearedPct, puzzles, d.world.getPlayers(), Math.max(0, d.witherKeys - d.witherDoorsOpened), d.bloodKey && !d.bloodDoorOpened);
    }

    private void keyPickup(Dungeon d, int room) {
        String name = d.actor != null ? d.actor.getName() : d.world.getPlayers().isEmpty() ? "Someone" : d.world.getPlayers().getFirst().getName();
        for (DoorType key : d.map.keysIn(room)) {
            boolean blood = key == DoorType.BLOOD;
            if (blood) d.bloodKey = true;
            else d.witherKeys++;
            for (Player p : d.world.getPlayers()) HypixelChat.keyPickup(name, blood).forEach(p::sendMessage);
        }
    }

    private void spawnMort(Dungeon d) {
        Location at = new Location(d.world, d.plan.mortX, d.plan.mortY, d.plan.mortZ, d.plan.mortYaw, 0);
        d.world.spawn(at, org.bukkit.entity.Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.getPersistentDataContainer().set(mortKey(), PersistentDataType.BYTE, (byte) 1);
        });
        nameTag(d.world, at.clone().add(0, 2.0, 0), Component.text("Mort", NamedTextColor.AQUA));
        nameTag(d.world, at.clone().add(0, 1.75, 0), Component.text("CLICK", NamedTextColor.YELLOW, net.kyori.adventure.text.format.TextDecoration.BOLD));
    }

    private NamespacedKey mortKey() {
        return new NamespacedKey(plugin, "mort");
    }

    @EventHandler
    public void onClickMort(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        Dungeon d = dungeonOf(event.getRightClicked().getWorld());
        if (d == null || !event.getRightClicked().getPersistentDataContainer().has(mortKey())) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) start(d, event.getPlayer());
    }

    private static void nameTag(World world, Location at, Component name) {
        world.spawn(at, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setPersistent(true);
            stand.customName(name);
            stand.setCustomNameVisible(true);
        });
    }

    private void start(Dungeon d, Player by) {
        if (d.startedAt >= 0) return;
        d.actor = by;
        d.startedAt = System.nanoTime();
        d.runSpeed.clear();
        for (Player p : d.world.getPlayers()) {
            int speed = Speed.get(p);
            d.runSpeed.put(p.getUniqueId(), speed);
            Speed.apply(p, speed);
            giveMap(d, p);
            p.sendMessage(HypixelChat.mort("Here, I found this map when I first entered the dungeon."));
        }
        if (d.plan.entranceDoor >= 0) {
            open(d, d.plan.entranceDoor, by);
        }
        d.map.start();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : d.world.getPlayers()) p.sendMessage(HypixelChat.mort("You should find it useful if you get lost."));
        }, 40);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : d.world.getPlayers()) p.sendMessage(HypixelChat.mort("Good luck."));
        }, 70);
        for (Player p : d.world.getPlayers()) {
            p.showTitle(Title.title(Component.text("Go!", NamedTextColor.GREEN), Component.empty(),
                Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(700), java.time.Duration.ofMillis(300))));
        }
    }

    private void finish(Dungeon d, Player by, long millis) {
        d.finished = true;
        d.finishedMillis = millis;
        String seed = d.seed.code();
        String floor = d.seed.floor().name();
        String time = Times.format(millis);
        List<Player> runners = d.world.getPlayers().stream().filter(p -> d.runSpeed.containsKey(p.getUniqueId())).toList();
        for (Player p : d.world.getPlayers()) {
            p.showTitle(Title.title(Component.text(time, NamedTextColor.GOLD), Component.text(by.getName() + " reached blood", NamedTextColor.GRAY)));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        long now = System.currentTimeMillis();
        for (Player p : runners) {
            Times.Clear seedBest = times.best(p.getUniqueId(), Times.onSeed(seed, floor));
            Times.Clear floorBest = times.best(p.getUniqueId(), Times.onFloor(floor));
            int speed = d.runSpeed.get(p.getUniqueId());
            times.add(new Times.Clear(p.getUniqueId(), p.getName(), seed, floor, millis, speed, runners.size(), now));
            String vsSeed = seedBest == null ? "first clear on this seed" : millis < seedBest.millis() ? "new best, was " + Times.format(seedBest.millis()) : "best is " + Times.format(seedBest.millis());
            p.sendMessage(Component.text(time + " on ", NamedTextColor.GREEN).append(code(d.seed))
                .append(Component.text(" at \u2726" + speed + " (" + vsSeed + ")", NamedTextColor.GRAY)));
            if (floorBest == null || millis < floorBest.millis()) {
                int rank = rank(p, floor);
                String where = rank > 0 && rank <= 10 ? " You're #" + rank + " on the board." : rank > 0 ? " You're #" + rank + " overall." : "";
                p.sendMessage(Component.text("New personal best!", NamedTextColor.GOLD).append(Component.text(where, NamedTextColor.YELLOW)));
            }
        }
        if (runners.isEmpty()) by.sendMessage(Component.text("Not saved: start the run at Mort for it to count.", NamedTextColor.GRAY));
        for (Player p : d.world.getPlayers()) {
            p.sendMessage(Component.text("Secrets found: " + d.secrets.totalFound() + "/" + d.secrets.totalAll(), NamedTextColor.GRAY));
            showTimes(p, d.seed);
            p.sendMessage(Component.text("/bc reset to run it again, /bc leave to go back.", NamedTextColor.DARK_GRAY));
        }
        int secretsPct = d.secrets.totalAll() == 0 ? 100 : 100 * d.secrets.totalFound() / d.secrets.totalAll();
        int clearedPct = Math.min(100, 100 * d.map.clearedCount() / Math.max(1, d.map.clearable()));
        int bonus = d.secrets.mimicKilled() ? 1 : 0;
        for (Player p : d.world.getPlayers()) HypixelChat.end(d.seed.floor(), clearedPct, secretsPct, bonus).forEach(p::sendMessage);
        lobby.refreshBoards();
    }

    private int rank(Player player, String floor) {
        List<Times.Clear> all = times.top(Times.onFloor(floor), Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) if (all.get(i).player().equals(player.getUniqueId())) return i + 1;
        return -1;
    }

    private void open(Dungeon d, int index, Player by) {
        d.open[index] = true;
        d.actor = by;
        DungeonPlan.Door door = d.plan.doors.get(index);
        if (door.type() == DoorType.WITHER) d.witherDoorsOpened++;
        if (door.type() == DoorType.BLOOD) d.bloodDoorOpened = true;
        for (Player p : d.world.getPlayers()) {
            if (door.type() == DoorType.WITHER) p.sendMessage(HypixelChat.witherDoor(by.getName()));
            if (door.type() == DoorType.BLOOD) HypixelChat.bloodDoor().forEach(p::sendMessage);
        }
        d.map.open(door.roomA());
        d.map.open(door.roomB());
        setMiddle(d, door, org.bukkit.Material.AIR.createBlockData());
        d.world.playSound(new Location(d.world, door.x() + 0.5, DungeonPlan.DOOR_Y + 1, door.z() + 0.5),
            door.type() == DoorType.BLOOD ? Sound.ENTITY_WITHER_SPAWN : Sound.BLOCK_IRON_DOOR_OPEN, 0.6f, 1f);
    }

    private void setMiddle(Dungeon d, DungeonPlan.Door door, BlockData block) {
        for (int y = DungeonPlan.DOOR_Y; y <= DungeonPlan.DOOR_Y + 3; y++) {
            for (int z = door.z() - 1; z <= door.z() + 1; z++) {
                for (int x = door.x() - 1; x <= door.x() + 1; x++) d.world.getBlockAt(x, y, z).setBlockData(block, false);
            }
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (ours(event.getPlayer().getWorld()) && event.getHand() == EquipmentSlot.HAND && event.getAction() != Action.PHYSICAL
            && DungeonItems.isTerminator(plugin, event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            DungeonItems.shootTerminator(plugin, event.getPlayer());
            return;
        }
        if (ours(event.getPlayer().getWorld()) && event.getHand() == EquipmentSlot.HAND
            && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            org.bukkit.inventory.ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
            if (DungeonItems.isAotv(plugin, held)) {
                event.setCancelled(true);
                DungeonItems.useAotv(event.getPlayer());
                return;
            }
            if (DungeonItems.isHyperion(plugin, held)) {
                event.setCancelled(true);
                DungeonItems.useHyperion(event.getPlayer());
                return;
            }
        }
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (event.getHand() == EquipmentSlot.HAND && (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_BLOCK)
            && ours(block.getWorld()) && puzzles.click(event.getPlayer(), block, event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            return;
        }
        Dungeon d = dungeonOf(block.getWorld());
        if (d == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && DungeonItems.isBreaker(plugin, event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            dungeonbreak(d, event.getPlayer(), block);
            return;
        }
        if (DungeonSecrets.isSuperboom(plugin, event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            d.secrets.superboom(event.getPlayer(), block);
            return;
        }
        for (int i = 0; i < d.open.length; i++) {
            DungeonPlan.Door door = d.plan.doors.get(i);
            if (d.open[i] || door.type() == DoorType.ENTRANCE) continue;
            if (!door.inMiddle(block.getX(), block.getY(), block.getZ())) continue;
            event.setCancelled(true);
            open(d, i, event.getPlayer());
            return;
        }
        if (d.secrets.onClick(event.getPlayer(), block, event.getAction() == Action.RIGHT_CLICK_BLOCK)) event.setCancelled(true);
    }

    private void dungeonbreak(Dungeon d, Player player, Block block) {
        int room = d.plan.roomIdAt(block.getX(), block.getZ());
        if (room < 0 || !d.map.isOpened(room) || !d.plan.insideRoom(room, block.getX(), block.getZ())) return;
        if (d.plan.placementOf(room).room().type() == Layout.RoomType.PUZZLE) return;
        for (DungeonPlan.Door door : d.plan.doors) if (door.inMiddle(block.getX(), block.getY(), block.getZ())) return;
        if (!DungeonItems.hasCharge(player) || !d.secrets.breakTemporarily(block)) return;
        DungeonItems.takeCharge(plugin, player);
    }

    @EventHandler
    public void onMelee(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !ours(player.getWorld())) return;
        if (!DungeonItems.isTerminator(plugin, player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        DungeonItems.shootTerminator(plugin, player);
    }

    @EventHandler
    public void onArrowHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(DungeonItems.arrowKey(plugin))) return;
        if (event.getEntity().getShooter() instanceof Player shooter) {
            Puzzles.ArrowResult result = puzzles.arrow(shooter, event.getHitEntity(), event.getHitBlock());
            if (result == Puzzles.ArrowResult.PASS) {
                event.setCancelled(true);
                return;
            }
            if (result == Puzzles.ArrowResult.TAKEN) {
                event.setCancelled(true);
                event.getEntity().remove();
                return;
            }
        }
        if (event.getHitEntity() instanceof org.bukkit.entity.LivingEntity target && !(target instanceof Player)
            && event.getEntity().getShooter() instanceof Player shooter) {
            event.setCancelled(true);
            target.damage(10_000, shooter);
        }
        event.getEntity().remove();
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Dungeon d = dungeonOf(event.getItem().getWorld());
        if (d != null) d.secrets.onPickup(event.getItem());
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Dungeon d = dungeonOf(event.getEntity().getWorld());
        if (d == null || !d.secrets.onDeath(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (dungeonOf(event.getBlock().getWorld()) != null && event.getPlayer().getGameMode() != GameMode.CREATIVE) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (dungeonOf(event.getBlock().getWorld()) != null && event.getPlayer().getGameMode() != GameMode.CREATIVE) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && dungeonOf(event.getEntity().getWorld()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onPhysics(org.bukkit.event.block.BlockPhysicsEvent event) {
        if (ours(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onLeavesDecay(org.bukkit.event.block.LeavesDecayEvent event) {
        if (ours(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onFlow(org.bukkit.event.block.BlockFromToEvent event) {
        if (ours(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onFade(org.bukkit.event.block.BlockFadeEvent event) {
        if (ours(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onSpread(org.bukkit.event.block.BlockSpreadEvent event) {
        if (ours(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onGrow(org.bukkit.event.block.BlockGrowEvent event) {
        if (ours(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onForm(org.bukkit.event.block.BlockFormEvent event) {
        if (ours(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onBurn(org.bukkit.event.block.BlockBurnEvent event) {
        if (ours(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onIgnite(org.bukkit.event.block.BlockIgniteEvent event) {
        if (ours(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onItemSpawn(org.bukkit.event.entity.ItemSpawnEvent event) {
        if (dungeonOf(event.getEntity().getWorld()) == null) return;
        if (!event.getEntity().getItemStack().hasItemMeta()
            || !event.getEntity().getItemStack().getItemMeta().getPersistentDataContainer().has(DungeonSecrets.itemTag(plugin))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        if (dungeonOf(event.getEntity().getWorld()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Dungeon from = dungeonOf(event.getFrom());
        if (from == null) return;
        DungeonSecrets.stripItems(plugin, event.getPlayer(), dungeonOf(event.getPlayer().getWorld()) == null);
        if (!ours(event.getPlayer().getWorld())) {
            Speed.reset(event.getPlayer());
            restoreMode(event.getPlayer());
            look.hide(event.getPlayer());
        }
        cleanupLater(from);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        brand.forget(event.getPlayer());
        Dungeon d = dungeonOf(event.getPlayer().getWorld());
        if (d == null) return;
        sendHome(event.getPlayer());
        look.forget(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (dungeonOf(event.getPlayer().getWorld()) == null) restoreMode(event.getPlayer());
        Speed.reset(event.getPlayer());
        event.getPlayer().teleport(lobby.spawn());
    }

    void sendHome(Player player) {
        DungeonSecrets.stripItems(plugin, player, true);
        player.teleport(lobby.spawn());
        Speed.reset(player);
        restoreMode(player);
    }

    void rememberMode(Player player) {
        if (!player.getPersistentDataContainer().has(previousMode)) {
            player.getPersistentDataContainer().set(previousMode, PersistentDataType.STRING, player.getGameMode().name());
        }
    }

    boolean ours(World world) {
        return dungeonOf(world) != null || viewer.isView(world);
    }

    void unloadAndDelete(World world) {
        Path folder = world.getWorldPath();
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().warning("Couldn't unload " + world.getName());
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteFolder(folder));
    }

    public void view(Player player, String room) {
        if (room == null) viewer.list(player);
        else viewer.view(player, room);
    }

    public List<String> recordedRooms() {
        return viewer.rooms();
    }

    void restoreMode(Player player) {
        String mode = player.getPersistentDataContainer().get(previousMode, PersistentDataType.STRING);
        if (mode == null) return;
        player.getPersistentDataContainer().remove(previousMode);
        try {
            player.setGameMode(GameMode.valueOf(mode));
        } catch (IllegalArgumentException ignored) {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    private void cleanupLater(Dungeon d) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (dungeons.get(d.world.getName()) == d && d.world.getPlayers().isEmpty()) remove(d, true);
        }, 20);
    }

    private void remove(Dungeon d, boolean async) {
        dungeons.remove(d.world.getName());
        puzzles.remove(d.world);
        Path folder = d.world.getWorldPath();
        if (!Bukkit.unloadWorld(d.world, false)) {
            plugin.getLogger().warning("Couldn't unload " + d.world.getName());
            return;
        }
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteFolder(folder));
        else deleteFolder(folder);
    }

    private void deleteLeftoverWorlds() {
        Path container = Bukkit.getWorldContainer().toPath();
        try (Stream<Path> found = Files.walk(container, 4)) {
            found.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().matches(PREFIX + "[a-z0-9_]+"))
                .filter(p -> Files.exists(p.resolve("level.dat")) || Files.isDirectory(p.resolve("region")))
                .toList()
                .forEach(this::deleteFolder);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't look for old map worlds: " + e.getMessage());
        }
    }

    private void deleteFolder(Path folder) {
        if (!folder.getFileName().toString().startsWith(PREFIX)) return;
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't delete " + folder + ": " + e.getMessage());
        }
    }

    private static Component code(Seed seed) {
        String code = seed.code();
        return Component.text(seed.code(), NamedTextColor.AQUA)
            .clickEvent(ClickEvent.copyToClipboard(code))
            .hoverEvent(HoverEvent.showText(Component.text("Click to copy")));
    }
}
