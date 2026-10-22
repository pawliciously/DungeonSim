package dev.pawfect.bloodcamp.dungeon;

import dev.pawfect.bloodcamp.dungeon.DungeonPlan.Secret;
import dev.pawfect.bloodcamp.dungeon.DungeonPlan.SecretKind;
import dev.pawfect.bloodcamp.gen.Rng;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

final class DungeonSecrets {
    private static final double BLAST_RADIUS = 5.0;
    private static final int BLAST_MAX_BLOCKS = 250;
    private static final long SUPERBOOM_COOLDOWN_MS = 300;

    private record SecretItem(String id, String name, Material material, NamedTextColor color) {}

    private static final List<SecretItem> SECRET_ITEMS = List.of(
        new SecretItem("SPIRIT_LEAP", "Spirit Leap", Material.ENDER_PEARL, NamedTextColor.BLUE),
        new SecretItem("DUNGEON_DECOY", "Decoy", Material.PLAYER_HEAD, NamedTextColor.GREEN),
        new SecretItem("INFLATABLE_JERRY", "Inflatable Jerry", Material.VILLAGER_SPAWN_EGG, NamedTextColor.WHITE),
        new SecretItem("TRAINING_WEIGHTS", "Training Weights", Material.PLAYER_HEAD, NamedTextColor.WHITE),
        new SecretItem("DEFUSE_KIT", "Defuse Kit", Material.TRIPWIRE_HOOK, NamedTextColor.WHITE),
        new SecretItem("TREASURE_TALISMAN", "Treasure Talisman", Material.PLAYER_HEAD, NamedTextColor.BLUE));

    private final JavaPlugin plugin;
    private final World world;
    private final DungeonPlan plan;
    private final NamespacedKey tag;
    private final boolean[] found;
    private final int[] foundIn;
    private final Map<Long, Integer> blockSecrets = new HashMap<>();
    private final Map<Long, Integer> keySlots = new HashMap<>();
    private final Map<java.util.UUID, Integer> entities = new HashMap<>();
    private final Map<Long, BlockState> changed = new LinkedHashMap<>();
    private final Map<java.util.UUID, Long> lastBoom = new HashMap<>();
    private final Set<Integer> waitingBats = new HashSet<>();
    private final Map<Long, Broken> broken = new LinkedHashMap<>();
    private java.util.UUID mimic;
    private boolean mimicKilled;
    private final RoomLibrary.Mimic mimicLook;

    private record Broken(BlockState state, long restoreAt) {}

    private static final double BAT_SPAWN_RANGE = 12.0;
    private static final int MIMIC_SPEED = 500;
    static final long BREAK_RESTORE_MS = 10_000;

    DungeonSecrets(JavaPlugin plugin, World world, DungeonPlan plan, RoomLibrary.Mimic mimicLook) {
        this.plugin = plugin;
        this.world = world;
        this.plan = plan;
        this.mimicLook = mimicLook;
        this.tag = new NamespacedKey(plugin, "secret");
        this.found = new boolean[plan.secrets.size()];
        this.foundIn = new int[plan.placements.size()];
        for (int i = 0; i < plan.secrets.size(); i++) {
            Secret s = plan.secrets.get(i);
            switch (s.kind()) {
                case CHEST, WITHER, KEY -> blockSecrets.put(key(s.x(), s.y(), s.z()), i);
                case KEY_SLOT -> {
                    keySlots.put(key(s.x(), s.y(), s.z()), i);
                    keySlots.put(key(s.x(), s.y() - 1, s.z()), i);
                }
                default -> { }
            }
        }
    }

    static NamespacedKey itemTag(JavaPlugin plugin) {
        return new NamespacedKey(plugin, "item");
    }

    void start() {
        for (DungeonPlan.Head h : plan.heads) h.profile().applyTo(world.getBlockAt(h.x(), h.y(), h.z()));
        int min = DungeonPlan.GRID >> 4;
        int maxX = (DungeonPlan.GRID + plan.layout.width * DungeonPlan.CELL) >> 4;
        int maxZ = (DungeonPlan.GRID + plan.layout.height * DungeonPlan.CELL) >> 4;
        for (int cx = min; cx <= maxX; cx++) for (int cz = min; cz <= maxZ; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        spawnEntities();
    }

    void reset() {
        restoreBroken(Long.MAX_VALUE);
        List<BlockState> states = new ArrayList<>(changed.values());
        changed.clear();
        for (BlockState state : states) state.update(true, false);
        for (int i = 0; i < found.length; i++) {
            Secret s = plan.secrets.get(i);
            if (s.kind() == SecretKind.CHEST && world.getBlockAt(s.x(), s.y(), s.z()).getState() instanceof Lidded chest) chest.close();
            found[i] = false;
        }
        java.util.Arrays.fill(foundIn, 0);
        for (java.util.UUID id : entities.keySet()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        entities.clear();
        waitingBats.clear();
        Entity spawnedMimic = mimic == null ? null : Bukkit.getEntity(mimic);
        if (spawnedMimic != null) spawnedMimic.remove();
        mimic = null;
        mimicKilled = false;
        for (Player p : world.getPlayers()) stripItems(p, false);
        spawnEntities();
    }

    private void spawnEntities() {
        Rng rng = new Rng(plan.layout.seed.rngSeed() ^ 0x6A09E667F3BCC909L);
        for (int i = 0; i < plan.secrets.size(); i++) {
            Secret s = plan.secrets.get(i);
            final int index = i;
            Location at = new Location(world, s.x() + 0.5, s.y() + 0.1, s.z() + 0.5);
            if (s.kind() == SecretKind.ITEM) {
                SecretItem pick = rng.pick(SECRET_ITEMS);
                Item item = world.dropItem(at, secretItem(pick), it -> {
                    it.setVelocity(new Vector());
                    it.setPickupDelay(0);
                    it.setUnlimitedLifetime(true);
                    it.setCanMobPickup(false);
                    it.setPersistent(true);
                    it.getPersistentDataContainer().set(tag, PersistentDataType.INTEGER, index);
                });
                entities.put(item.getUniqueId(), i);
            } else if (s.kind() == SecretKind.BAT) {
                waitingBats.add(i);
            }
        }
    }

    void spawnNearbyBats(java.util.function.IntPredicate placementOpened) {
        if (waitingBats.isEmpty()) return;
        List<Player> players = world.getPlayers();
        for (java.util.Iterator<Integer> it = waitingBats.iterator(); it.hasNext(); ) {
            int index = it.next();
            Secret s = plan.secrets.get(index);
            if (found[index] || !placementOpened.test(s.placement())) continue;
            Location at = new Location(world, s.x() + 0.5, s.y() + 0.1, s.z() + 0.5);
            boolean near = false;
            for (Player p : players) {
                if (p.getLocation().distanceSquared(at) <= BAT_SPAWN_RANGE * BAT_SPAWN_RANGE) near = true;
            }
            if (!near) continue;
            it.remove();
            Bat bat = world.spawn(at, Bat.class, b -> {
                b.setGlowing(true);
                b.setPersistent(true);
                b.setRemoveWhenFarAway(false);
                b.setAwake(true);
                AttributeInstance health = b.getAttribute(Attribute.MAX_HEALTH);
                if (health != null) health.setBaseValue(1);
                b.setHealth(1);
                b.getPersistentDataContainer().set(tag, PersistentDataType.INTEGER, index);
            });
            entities.put(bat.getUniqueId(), index);
        }
    }

    boolean breakTemporarily(Block block) {
        Material type = block.getType();
        if (type.isAir() || block.isLiquid() || type == Material.BARRIER || type == Material.BEDROCK) return false;
        long key = key(block.getX(), block.getY(), block.getZ());
        if (blockSecrets.containsKey(key) || keySlots.containsKey(key)) return false;
        BlockState state = block.getState();
        if (state instanceof org.bukkit.block.TileState) return false;
        broken.put(key, new Broken(state, System.currentTimeMillis() + BREAK_RESTORE_MS));
        world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 30, 0.3, 0.3, 0.3, block.getBlockData());
        world.playSound(block.getLocation().add(0.5, 0.5, 0.5), block.getBlockData().getSoundGroup().getBreakSound(), 1f, 0.8f);
        block.setType(Material.AIR, false);
        return true;
    }

    void restoreBroken(long now) {
        if (broken.isEmpty()) return;
        for (java.util.Iterator<Broken> it = broken.values().iterator(); it.hasNext(); ) {
            Broken b = it.next();
            if (now != Long.MAX_VALUE && b.restoreAt() > now) continue;
            b.state().update(true, false);
            it.remove();
        }
    }

    private ItemStack secretItem(SecretItem item) {
        ItemStack stack = Bukkit.getItemFactory().createItemStack(
            "minecraft:" + item.material().getKey().getKey() + "[minecraft:custom_data={id:\"" + item.id() + "\"}]");
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(item.name(), item.color()).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(itemTag(plugin), PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    static ItemStack superboom(JavaPlugin plugin) {
        ItemStack stack = Bukkit.getItemFactory().createItemStack("minecraft:tnt[minecraft:custom_data={id:\"SUPERBOOM_TNT\"}]");
        stack.setAmount(64);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Superboom TNT", NamedTextColor.BLUE).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Breaks weak walls.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Click a cracked wall to use.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemTag(plugin), PersistentDataType.BYTE, (byte) 2);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack redstoneKey() {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        meta.setPlayerProfile(HeadProfile.REDSTONE_KEY.profile());
        meta.displayName(Component.text("Redstone Key", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(itemTag(plugin), PersistentDataType.BYTE, (byte) 3);
        stack.setItemMeta(meta);
        return stack;
    }

    static boolean isSuperboom(JavaPlugin plugin, ItemStack stack) {
        return tagOf(plugin, stack) == 2;
    }

    static byte tagOf(JavaPlugin plugin, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) return 0;
        Byte b = stack.getItemMeta().getPersistentDataContainer().get(itemTag(plugin), PersistentDataType.BYTE);
        return b == null ? 0 : b;
    }

    void stripItems(Player player, boolean superboomToo) {
        stripItems(plugin, player, superboomToo);
    }

    static void stripItems(JavaPlugin plugin, Player player, boolean superboomToo) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            byte t = tagOf(plugin, inv.getItem(slot));
            if (t == 1 || t == 3 || t == 4 || (superboomToo && (t == 2 || (t >= 5 && t <= 9)))) inv.setItem(slot, null);
        }
    }

    static void giveSuperboom(JavaPlugin plugin, Player player) {
        PlayerInventory inv = player.getInventory();
        for (ItemStack stack : inv.getContents()) if (isSuperboom(plugin, stack)) return;
        for (int slot = 0; slot < 8; slot++) {
            if (inv.getItem(slot) == null || inv.getItem(slot).isEmpty()) {
                inv.setItem(slot, superboom(plugin));
                return;
            }
        }
        inv.addItem(superboom(plugin));
    }

    int total(int placement) {
        return plan.secretTotal[placement];
    }

    int found(int placement) {
        return Math.min(foundIn[placement], plan.secretTotal[placement]);
    }

    int totalFound() {
        int n = 0;
        for (int i = 0; i < foundIn.length; i++) n += found(i);
        return n;
    }

    int totalAll() {
        int n = 0;
        for (int t : plan.secretTotal) n += t;
        return n;
    }

    private void markFound(int index) {
        if (found[index]) return;
        found[index] = true;
        Secret s = plan.secrets.get(index);
        if (s.kind().counted()) foundIn[s.placement()]++;
    }

    boolean onClick(Player player, Block block, boolean rightClick) {
        long key = key(block.getX(), block.getY(), block.getZ());
        Integer index = blockSecrets.get(key);
        if (index != null && rightClick) {
            Secret s = plan.secrets.get(index);
            switch (s.kind()) {
                case CHEST -> openChest(player, block, index);
                case WITHER -> {
                    if (!isHead(block)) return false;
                    remember(block);
                    block.setType(Material.AIR, false);
                    world.playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_BAT_TAKEOFF, 0.6f, 1.6f);
                    markFound(index);
                }
                case KEY -> {
                    if (!isHead(block)) return false;
                    remember(block);
                    block.setType(Material.AIR, false);
                    player.getInventory().addItem(redstoneKey());
                    world.playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1f);
                    markFound(index);
                }
                default -> {
                    return false;
                }
            }
            return true;
        }
        Integer slot = keySlots.get(key);
        if (slot != null && rightClick && tagOf(plugin, player.getInventory().getItemInMainHand()) == 3) {
            Secret s = plan.secrets.get(slot);
            Block head = world.getBlockAt(s.x(), s.y(), s.z());
            if (!head.getType().isAir()) return false;
            remember(head);
            head.setBlockData(Bukkit.createBlockData("minecraft:player_head[rotation=0]"), false);
            HeadProfile.REDSTONE_KEY.applyTo(head);
            ItemStack hand = player.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
            world.playSound(head.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 1f);
            markFound(slot);
            return true;
        }
        if (rightClick) rememberIfToggled(block);
        return false;
    }

    private static boolean isHead(Block block) {
        return block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD;
    }

    private void openChest(Player player, Block block, int index) {
        if (found[index]) {
            player.sendMessage(Component.text("This chest has already been searched!", NamedTextColor.RED));
            return;
        }
        markFound(index);
        if (index == plan.mimic) {
            remember(block);
            block.setType(Material.AIR, false);
            spawnMimic(block);
            return;
        }
        if (block.getState() instanceof Lidded chest) chest.open();
        world.playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_CHEST_OPEN, 0.6f, 1f);
    }

    private void spawnMimic(Block chest) {
        Zombie zombie = world.spawn(chest.getLocation().add(0.5, 0, 0.5), Zombie.class, false, z -> {
            z.setBaby();
            AttributeInstance speed = z.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) speed.removeModifier(NamespacedKey.minecraft("baby"));
            Speed.applyToMob(z, MIMIC_SPEED);
            z.setShouldBurnInDay(false);
            z.setPersistent(true);
            z.setRemoveWhenFarAway(false);
            if (mimicLook == null) return;
            if (mimicLook.name() != null) {
                z.customName(LegacyComponentSerializer.legacySection().deserialize(mimicLook.name()));
                z.setCustomNameVisible(true);
            }
            if (z.getEquipment() == null) return;
            mimicLook.equipment().forEach((slot, snbt) -> {
                EquipmentSlot s = RoomViewer.SLOTS.get(slot);
                ItemStack item = RoomViewer.item(snbt);
                if (s == null || item == null) return;
                z.getEquipment().setItem(s, item);
                z.getEquipment().setDropChance(s, 0f);
            });
        });
        mimic = zombie.getUniqueId();
    }

    boolean mimicKilled() {
        return mimicKilled;
    }

    private void rememberIfToggled(Block block) {
        BlockData data = block.getBlockData();
        if (block.getType() != Material.LEVER && !(data instanceof Openable)) return;
        remember(block);
        if (data instanceof Door door) {
            remember(block.getRelative(door.getHalf() == Bisected.Half.BOTTOM ? org.bukkit.block.BlockFace.UP : org.bukkit.block.BlockFace.DOWN));
        }
    }

    void onPickup(Item item) {
        Integer index = item.getPersistentDataContainer().get(tag, PersistentDataType.INTEGER);
        if (index == null || index < 0 || index >= found.length) return;
        entities.remove(item.getUniqueId());
        markFound(index);
    }

    boolean onDeath(Entity entity) {
        if (entity.getUniqueId().equals(mimic)) {
            mimic = null;
            mimicKilled = true;
            return true;
        }
        Integer index = entity.getPersistentDataContainer().get(tag, PersistentDataType.INTEGER);
        if (index == null || index < 0 || index >= found.length) return false;
        entities.remove(entity.getUniqueId());
        markFound(index);
        return true;
    }

    void superboom(Player player, Block hit) {
        long now = System.currentTimeMillis();
        Long last = lastBoom.get(player.getUniqueId());
        if (last != null && now - last < SUPERBOOM_COOLDOWN_MS) return;
        lastBoom.put(player.getUniqueId(), now);

        Location centre = hit.getLocation().add(0.5, 0.5, 0.5);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, centre, 1);
        world.playSound(centre, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);

        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            Block b = hit.getRelative(dx, dy, dz);
            if (b.getType() == Material.CRACKED_STONE_BRICKS && dx * dx + dy * dy + dz * dz <= 4) queue.add(b);
        }
        int broken = 0;
        while (!queue.isEmpty() && broken < BLAST_MAX_BLOCKS) {
            Block b = queue.poll();
            if (!seen.add(key(b.getX(), b.getY(), b.getZ()))) continue;
            if (b.getType() != Material.CRACKED_STONE_BRICKS) continue;
            if (b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(centre) > BLAST_RADIUS * BLAST_RADIUS) continue;
            remember(b);
            b.setType(Material.AIR, false);
            broken++;
            for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN, org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                queue.add(b.getRelative(face));
            }
        }
    }

    private void remember(Block block) {
        changed.putIfAbsent(key(block.getX(), block.getY(), block.getZ()), block.getState());
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
