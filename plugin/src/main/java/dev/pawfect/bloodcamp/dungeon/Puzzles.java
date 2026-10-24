package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class Puzzles implements Listener {
    static final boolean WATERBOARD = false;

    final JavaPlugin plugin;
    final NamespacedKey key;
    private final Map<String, List<Puzzle>> byWorld = new HashMap<>();
    private BukkitTask ticker;
    interface Solved {
        void solved(Player by, String name, long millis);
    }
    Solved onSolved = (p, n, ms) -> { };

    Puzzles(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "puzzle");
    }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    void disable() {
        if (ticker != null) ticker.cancel();
        for (List<Puzzle> list : byWorld.values()) for (Puzzle p : list) p.clear();
        byWorld.clear();
    }

    Puzzle add(World world, Puzzle.Frame frame, String room, String name, int roomIndex, Consumer<Puzzle> listener) {
        Puzzle puzzle = create(world, frame, room == null ? "" : room.toLowerCase(), name, roomIndex);
        if (puzzle == null) return null;
        puzzle.listener = listener;
        puzzle.prepare();
        byWorld.computeIfAbsent(world.getName(), w -> new ArrayList<>()).add(puzzle);
        return puzzle;
    }

    private Puzzle create(World world, Puzzle.Frame frame, String room, String name, int roomIndex) {
        if (room.equals("three-chests")) return new PuzzleWeirdos(this, world, frame, room, name, roomIndex);
        if (room.startsWith("blaze-room")) return new PuzzleBlaze(this, world, frame, room, name, roomIndex);
        if (room.equals("ice-path")) return new PuzzleIceFill(this, world, frame, room, name, roomIndex);
        if (room.equals("ice-silverfish-room")) return new PuzzleSilverfish(this, world, frame, room, name, roomIndex);
        if (room.startsWith("tic-tac-toe")) return new PuzzleTicTacToe(this, world, frame, room, name, roomIndex);
        if (room.equals("trivia-room")) return new PuzzleQuiz(this, world, frame, room, name, roomIndex);
        if (room.equals("boxes-room")) return new PuzzleBoulder(this, world, frame, room, name, roomIndex);
        if (room.equals("teleport-pad-room")) return new PuzzleTeleport(this, world, frame, room, name, roomIndex);
        if (room.equals("creeper-room")) return new PuzzleCreeper(this, world, frame, room, name, roomIndex);
        if (room.equals("water-puzzle")) return WATERBOARD ? new PuzzleWaterboard(this, world, frame, room, name, roomIndex) : null;
        return null;
    }

    boolean waterZone(Block block) {
        for (Puzzle p : in(block.getWorld())) if (p instanceof PuzzleWaterboard w && w.waterZone(block)) return true;
        return false;
    }

    boolean waterAllowed(Block block, Block to) {
        if (to != null) return waterZone(block) && waterZone(to);
        return block.getType() == org.bukkit.Material.WATER && waterZone(block);
    }

    boolean noteleport(org.bukkit.Location l) {
        if (l == null || l.getWorld() == null) return false;
        for (Puzzle p : in(l.getWorld())) if (p instanceof PuzzleTeleport t && t.noteleport(l)) return true;
        return false;
    }
    List<Puzzle> in(World world) {
        return byWorld.getOrDefault(world.getName(), List.of());
    }

    Puzzle of(World world, int roomIndex) {
        for (Puzzle p : in(world)) if (p.roomIndex == roomIndex) return p;
        return null;
    }

    void reset(World world) {
        for (Puzzle p : in(world)) p.reset();
    }

    void remove(World world) {
        List<Puzzle> list = byWorld.remove(world.getName());
        if (list != null) for (Puzzle p : list) p.clear();
    }

    private void tick() {
        for (List<Puzzle> list : byWorld.values()) {
            for (Puzzle p : list) {
                if (p.world.getPlayers().isEmpty()) continue;
                if (p.state == Puzzle.State.WAITING) {
                    for (Player player : p.world.getPlayers()) {
                        if (player.getGameMode() != org.bukkit.GameMode.SPECTATOR && p.inside(player.getLocation())) {
                            p.start();
                            break;
                        }
                    }
                }
                if (p.state == Puzzle.State.ACTIVE || (p.state == Puzzle.State.SOLVED && p.ticksWhenSolved())) p.tick();
            }
        }
    }

    boolean click(Player player, Block block, boolean right) {
        for (Puzzle p : in(block.getWorld())) {
            if (p.state == Puzzle.State.ACTIVE && p.inside(block.getLocation()) && p.click(player, block, right)) return true;
        }
        return false;
    }

    enum ArrowResult { IGNORED, TAKEN, PASS }

    ArrowResult arrow(Player player, Entity hitEntity, Block hitBlock, boolean aimed) {
        if (hitEntity != null) {
            Puzzle owner = owner(hitEntity);
            if (owner == null) return ArrowResult.IGNORED;
            return owner.state == Puzzle.State.ACTIVE && owner.hit(player, hitEntity, false) ? ArrowResult.TAKEN : ArrowResult.PASS;
        }
        if (hitBlock == null || !aimed) return ArrowResult.IGNORED;
        for (Puzzle p : in(hitBlock.getWorld())) {
            if (p.state == Puzzle.State.ACTIVE && p.inside(hitBlock.getLocation()) && p.arrow(player, hitBlock)) return ArrowResult.TAKEN;
        }
        return ArrowResult.IGNORED;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (!event.getAttacked().getPersistentDataContainer().has(key)) return;
        event.setCancelled(true);
        Puzzle owner = owner(event.getAttacked());
        if (owner != null && owner.state == Puzzle.State.ACTIVE) owner.hit(event.getPlayer(), event.getAttacked(), true);
    }

    private Puzzle owner(Entity entity) {
        if (!entity.getPersistentDataContainer().has(key)) return null;
        for (Puzzle p : in(entity.getWorld())) if (p.owns(entity)) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(key)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(key)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Puzzle owner = owner(event.getRightClicked());
        if (owner != null && owner.state == Puzzle.State.ACTIVE) owner.interact(event.getPlayer(), event.getRightClicked());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemSpawn(org.bukkit.event.entity.ItemSpawnEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(key)) event.setCancelled(false);
    }

    @EventHandler
    public void onHangingBreak(HangingBreakEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(key)) event.setCancelled(true);
    }

    @EventHandler
    public void onCombust(EntityCombustEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(key)) event.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(key)) event.setCancelled(true);
    }
}
