package dev.pawfect.bloodcamp.dungeon;

import io.papermc.paper.block.BlockPredicate;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAdventurePredicate;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

final class DungeonItems {
    private static final byte AOTV = 5;
    private static final byte PEARLS = 6;
    private static final byte HYPERION = 7;
    private static final byte BREAKER = 8;
    private static final byte TERMINATOR = 9;
    private static final byte SCEPTRE = 10;
    private static final int TERMINATOR_COOLDOWN_TICKS = 4;
    private static final double TERMINATOR_SPREAD = Math.toRadians(5);
    private static final float ARROW_SPEED = 3.0f;
    private static final Map<UUID, Integer> lastShot = new ConcurrentHashMap<>();

    private static final double EYE = 1.62;
    private static final double EYE_SNEAKING = 1.54;
    private static final int TUNERS = 4;
    private static final int INSTANT_RANGE = 8 + TUNERS;
    private static final int ETHER_RANGE = 57 + TUNERS;
    private static final int WITHER_IMPACT_RANGE = 10;
    private static final double IMPLOSION_RADIUS = 6;
    private static final long HYPERION_COOLDOWN_MS = 150;

    static final int MAX_CHARGES = 25;

    private static final double BAT_SPEED = 0.8;
    private static final int BAT_LIFETIME = 80;
    private static final double BAT_BLAST = 6;
    private static final int MAX_BATS = 15;
    private static final Map<UUID, Integer> flying = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> charges = new ConcurrentHashMap<>();

    private static final Set<Material> INSTANT_PASSABLE = EnumSet.noneOf(Material.class);
    private static final Set<Material> ETHER_PASSABLE = EnumSet.noneOf(Material.class);

    static {
        INSTANT_PASSABLE.addAll(List.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.WATER, Material.LAVA,
            Material.POWERED_RAIL, Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL, Material.RAIL, Material.COBWEB,
            Material.SHORT_GRASS, Material.FERN, Material.DEAD_BUSH, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
            Material.TORCH, Material.WALL_TORCH, Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH, Material.FIRE,
            Material.REDSTONE_WIRE, Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.LADDER, Material.LEVER,
            Material.SUGAR_CANE, Material.NETHER_PORTAL, Material.END_PORTAL, Material.REPEATER, Material.COMPARATOR,
            Material.MELON_STEM, Material.ATTACHED_MELON_STEM, Material.VINE, Material.NETHER_WART, Material.TRIPWIRE,
            Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY, Material.TALL_GRASS, Material.LARGE_FERN));
        INSTANT_PASSABLE.addAll(Tag.SAPLINGS.getValues());
        INSTANT_PASSABLE.addAll(Tag.SMALL_FLOWERS.getValues());
        INSTANT_PASSABLE.addAll(Tag.BUTTONS.getValues());
        INSTANT_PASSABLE.addAll(Tag.FLOWER_POTS.getValues());
        INSTANT_PASSABLE.addAll(Tag.WOOL_CARPETS.getValues());
        ETHER_PASSABLE.addAll(List.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.WATER, Material.LAVA,
            Material.COBWEB, Material.SHORT_GRASS, Material.FERN, Material.DEAD_BUSH, Material.MOVING_PISTON,
            Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.TORCH, Material.WALL_TORCH, Material.FIRE,
            Material.REDSTONE_WIRE, Material.WHEAT, Material.LADDER, Material.RAIL, Material.LEVER, Material.REDSTONE_TORCH,
            Material.REDSTONE_WALL_TORCH, Material.SNOW, Material.REPEATER, Material.PUMPKIN_STEM, Material.ATTACHED_PUMPKIN_STEM,
            Material.MELON_STEM, Material.ATTACHED_MELON_STEM, Material.VINE, Material.LILY_PAD, Material.NETHER_WART,
            Material.TRIPWIRE_HOOK, Material.TRIPWIRE, Material.CARROTS, Material.POTATOES, Material.COMPARATOR,
            Material.ACTIVATOR_RAIL, Material.SUNFLOWER, Material.LILAC, Material.TALL_GRASS, Material.LARGE_FERN,
            Material.ROSE_BUSH, Material.PEONY));
        ETHER_PASSABLE.addAll(Tag.SAPLINGS.getValues());
        ETHER_PASSABLE.addAll(Tag.SMALL_FLOWERS.getValues());
        ETHER_PASSABLE.addAll(Tag.BUTTONS.getValues());
        ETHER_PASSABLE.addAll(Tag.FLOWER_POTS.getValues());
        ETHER_PASSABLE.addAll(Tag.WOOL_CARPETS.getValues());
        for (Material m : Material.values()) {
            if (m.isBlock() && (m.name().endsWith("_HEAD") || m.name().endsWith("_SKULL"))) ETHER_PASSABLE.add(m);
        }
    }

    private DungeonItems() {}

    static ItemStack aotv(JavaPlugin plugin) {
        ItemStack stack = new ItemStack(Material.DIAMOND_SHOVEL);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Aspect of the Void", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore("""
            §6Ability: Instant Transmission §e§lRIGHT CLICK
            §7Teleport §a12 blocks §7ahead of you and
            §7gain §a+50 §f✦ Speed §7for §a3 seconds.

            §6Ability: Ether Transmission §e§lSNEAK RIGHT CLICK
            §7Teleport to your targeted block up
            §7to §a61 blocks §7away.

            §5§lEPIC SWORD"""));
        finish(plugin, meta, AOTV);
        stack.setItemMeta(meta);
        return withId(stack, tag -> {
            tag.putByte("ethermerge", (byte) 1);
            tag.putString("id", "ASPECT_OF_THE_VOID");
            tag.putInt("tuned_transmission", TUNERS);
        });
    }

    static ItemStack hyperion(JavaPlugin plugin) {
        ItemStack stack = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Hyperion", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore("""
            §aScroll Abilities:
            §6Ability: Wither Impact §e§lRIGHT CLICK
            §7Teleport §a10 blocks§7 ahead of you.
            §7Then implode dealing §c10,000
            §7damage to nearby enemies. Also
            §7applies the wither shield scroll
            §7ability reducing damage taken and
            §7granting an absorption shield for §e5
            §7seconds.

            §d§lMYTHIC DUNGEON SWORD"""));
        finish(plugin, meta, HYPERION);
        stack.setItemMeta(meta);
        return withId(stack, tag -> tag.putString("id", "HYPERION"));
    }

    static ItemStack terminator(JavaPlugin plugin) {
        ItemStack stack = new ItemStack(Material.STICK);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(NamespacedKey.minecraft("bow"));
        meta.setMaxStackSize(1);
        meta.displayName(Component.text("Terminator", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore("""
            §d§lDuplex V

            §7Shortbow: Instantly shoots!
            §7Shoots §b3 §7arrows at once.
            §7Can damage endermen.

            §6§lLEGENDARY DUNGEON BOW"""));
        finish(plugin, meta, TERMINATOR);
        stack.setItemMeta(meta);
        return withId(stack, tag -> {
            tag.putString("id", "TERMINATOR");
            CompoundTag enchants = new CompoundTag();
            enchants.putInt("ultimate_duplex", 5);
            tag.put("enchantments", enchants);
        });
    }

    static ItemStack sceptre(JavaPlugin plugin) {
        ItemStack stack = new ItemStack(Material.ALLIUM);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Spirit Sceptre", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore("""
            §6Ability: Guided Bat  §e§lRIGHT CLICK
            §7Shoots a guided spirit bat, following your aim
            §7and exploding for §c2,000 §7damage.

            §6§lLEGENDARY DUNGEON SWORD"""));
        finish(plugin, meta, SCEPTRE);
        stack.setItemMeta(meta);
        return withId(stack, tag -> tag.putString("id", "BAT_WAND"));
    }

    static boolean isSceptre(JavaPlugin plugin, ItemStack stack) {
        return DungeonSecrets.tagOf(plugin, stack) == SCEPTRE;
    }

    static NamespacedKey spiritKey(JavaPlugin plugin) {
        return new NamespacedKey(plugin, "spirit_bat");
    }

    static void useSceptre(JavaPlugin plugin, Player player) {
        UUID id = player.getUniqueId();
        if (flying.getOrDefault(id, 0) >= MAX_BATS) return;
        flying.merge(id, 1, Integer::sum);
        track.accept(player, "sceptre");
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Location start = eye.clone().add(eye.getDirection().multiply(0.6)).subtract(0, 0.45, 0);
        NamespacedKey puzzle = new NamespacedKey(plugin, "puzzle");
        Bat bat = world.spawn(start, Bat.class, b -> {
            b.setAI(false);
            b.setGravity(false);
            b.setInvulnerable(true);
            b.setSilent(true);
            b.setPersistent(false);
            b.setAwake(true);
            b.setCollidable(false);
            b.getPersistentDataContainer().set(spiritKey(plugin), PersistentDataType.BYTE, (byte) 1);
        });
        world.playSound(start, Sound.ENTITY_BAT_TAKEOFF, 0.6f, 1.3f);
        new org.bukkit.scheduler.BukkitRunnable() {
            int age;

            @Override
            public void cancel() {
                super.cancel();
                flying.computeIfPresent(id, (k, v) -> v > 1 ? v - 1 : null);
            }
            @Override
            public void run() {
                if (!bat.isValid() || !player.isOnline() || player.getWorld() != world) {
                    bat.remove();
                    cancel();
                    return;
                }
                Vector centre = bat.getLocation().toVector().add(new Vector(0, 0.45, 0));
                Location eyeNow = player.getEyeLocation();
                Vector aim = eyeNow.toVector().add(eyeNow.getDirection().multiply(centre.distance(eyeNow.toVector()) + BAT_SPEED * 2));
                Vector dir = aim.subtract(centre);
                if (dir.lengthSquared() < 1e-6) dir = eyeNow.getDirection();
                dir.normalize();
                org.bukkit.util.RayTraceResult wall = world.rayTraceBlocks(centre.toLocation(world), dir, BAT_SPEED,
                    org.bukkit.FluidCollisionMode.NEVER, true);
                Vector next = wall != null ? wall.getHitPosition() : centre.clone().add(dir.clone().multiply(BAT_SPEED));
                boolean mob = false;
                for (Entity e : world.getNearbyEntities(next.toLocation(world), 0.7, 0.7, 0.7)) {
                    if (target(e, plugin, puzzle)) mob = true;
                }
                if (wall != null || mob || ++age >= BAT_LIFETIME) {
                    explode(player, next.toLocation(world), plugin, puzzle);
                    bat.remove();
                    cancel();
                    return;
                }
                Location to = next.toLocation(world).subtract(0, 0.45, 0);
                to.setDirection(dir);
                bat.teleport(to);
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    private static boolean target(Entity e, JavaPlugin plugin, NamespacedKey puzzle) {
        if (!(e instanceof Bat) && !(e instanceof Monster)) return false;
        if (e.isDead() || e.getPersistentDataContainer().has(spiritKey(plugin))) return false;
        return !e.getPersistentDataContainer().has(puzzle);
    }

    private static void explode(Player player, Location at, JavaPlugin plugin, NamespacedKey puzzle) {
        World world = at.getWorld();
        world.spawnParticle(Particle.EXPLOSION, at, 3, 0.4, 0.4, 0.4, 0);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.1f);
        for (Entity e : world.getNearbyEntities(at, BAT_BLAST, BAT_BLAST, BAT_BLAST)) {
            if (!target(e, plugin, puzzle) || e.getLocation().distanceSquared(at) > BAT_BLAST * BAT_BLAST) continue;
            ((org.bukkit.entity.LivingEntity) e).damage(10_000, player);
        }
    }

    static boolean isTerminator(JavaPlugin plugin, ItemStack stack) {
        return DungeonSecrets.tagOf(plugin, stack) == TERMINATOR;
    }

    static final byte AIMED_ARROW = 1;

    static NamespacedKey arrowKey(JavaPlugin plugin) {
        return new NamespacedKey(plugin, "terminator_arrow");
    }

    static void shootTerminator(JavaPlugin plugin, Player player) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastShot.get(player.getUniqueId());
        if (last != null && now - last < TERMINATOR_COOLDOWN_TICKS) return;
        lastShot.put(player.getUniqueId(), now);
        track.accept(player, "terminator");
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        for (double angle : new double[] {-TERMINATOR_SPREAD, 0, TERMINATOR_SPREAD}) {
            arrow(plugin, player, eye, look.clone().rotateAroundY(angle), angle == 0);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) arrow(plugin, player, player.getEyeLocation(), player.getEyeLocation().getDirection(), true);
        }, 2);
        player.getWorld().playSound(eye, Sound.ENTITY_ARROW_SHOOT, 1f, 1f);
    }

    private static void arrow(JavaPlugin plugin, Player player, Location eye, Vector direction, boolean aimed) {
        org.bukkit.entity.Arrow arrow = player.getWorld().spawnArrow(eye.clone().add(direction.clone().multiply(0.3)), direction, ARROW_SPEED, 0f);
        arrow.setShooter(player);
        arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setCritical(true);
        arrow.setPersistent(false);
        arrow.setPierceLevel(127);
        arrow.getPersistentDataContainer().set(arrowKey(plugin), PersistentDataType.BYTE, aimed ? AIMED_ARROW : (byte) 2);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (arrow.isValid()) arrow.remove();
        }, 60);
    }

    static ItemStack breaker(JavaPlugin plugin, int charge) {
        ItemStack stack = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Dungeonbreaker", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(breakerLore(charge));
        finish(plugin, meta, BREAKER);
        stack.setItemMeta(meta);
        stack.setData(DataComponentTypes.CAN_BREAK, ItemAdventurePredicate.itemAdventurePredicate(List.of(BlockPredicate.predicate().build())));
        stack.setData(DataComponentTypes.TOOL, io.papermc.paper.datacomponent.item.Tool.tool().defaultMiningSpeed(1_000_000f).damagePerBlock(0).build());
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.CAN_BREAK).build());
        chargeBar(stack, charge);
        return withId(stack, tag -> tag.putString("id", "DUNGEONBREAKER"));
    }

    private static void chargeBar(ItemStack stack, int charge) {
        stack.unsetData(DataComponentTypes.UNBREAKABLE);
        stack.setData(DataComponentTypes.MAX_DAMAGE, MAX_CHARGES + 1);
        stack.setData(DataComponentTypes.DAMAGE, MAX_CHARGES - Math.max(0, Math.min(MAX_CHARGES, charge)));
    }

    private static List<Component> breakerLore(int charge) {
        return lore("""
            §7Speed: §a+20

            §6Ability: Dungeon Breaker §e§lDIG
            §7Temporarily break blocks in Dungeons,
            §7using §a1 §7charge per block. Blocks
            §7reappear after §a10s§7.
            §7Charges: §e%d§7/§e%d §8(2/s)

            §c§lSPECIAL DUNGEON PICKAXE""".formatted(charge, MAX_CHARGES));
    }

    static ItemStack pearls(JavaPlugin plugin) {
        ItemStack stack = new ItemStack(Material.ENDER_PEARL, 16);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(DungeonSecrets.itemTag(plugin), PersistentDataType.BYTE, PEARLS);
        stack.setItemMeta(meta);
        return stack;
    }

    private static List<Component> lore(String text) {
        List<Component> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            lines.add(LegacyComponentSerializer.legacySection().deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        return lines;
    }

    private static void finish(JavaPlugin plugin, ItemMeta meta, byte tag) {
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(DungeonSecrets.itemTag(plugin), PersistentDataType.BYTE, tag);
    }

    private static ItemStack withId(ItemStack stack, Consumer<CompoundTag> fill) {
        net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(stack);
        CustomData.update(DataComponents.CUSTOM_DATA, nms, fill);
        return CraftItemStack.asBukkitCopy(nms);
    }

    static boolean isAotv(JavaPlugin plugin, ItemStack stack) {
        return DungeonSecrets.tagOf(plugin, stack) == AOTV;
    }

    static boolean isHyperion(JavaPlugin plugin, ItemStack stack) {
        return DungeonSecrets.tagOf(plugin, stack) == HYPERION;
    }

    static boolean isBreaker(JavaPlugin plugin, ItemStack stack) {
        return DungeonSecrets.tagOf(plugin, stack) == BREAKER;
    }

    static void giveKit(JavaPlugin plugin, Player player) {
        charges.put(player.getUniqueId(), MAX_CHARGES);
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            byte t = DungeonSecrets.tagOf(plugin, inv.getItem(slot));
            if (t == 2 || (t >= AOTV && t <= SCEPTRE)) inv.setItem(slot, null);
        }
        for (Map.Entry<Byte, Integer> e : Loadout.layout(plugin, player).entrySet()) {
            if (e.getKey() == 4) continue;
            place(inv, e.getValue(), kitItem(plugin, e.getKey()));
        }
    }

    static void place(PlayerInventory inv, int slot, ItemStack stack) {
        ItemStack at = inv.getItem(slot);
        if (at == null || at.isEmpty()) inv.setItem(slot, stack);
        else put(inv, stack);
    }

    static ItemStack kitItem(JavaPlugin plugin, byte tag) {
        return switch (tag) {
            case 2 -> DungeonSecrets.superboom(plugin);
            case AOTV -> aotv(plugin);
            case PEARLS -> pearls(plugin);
            case HYPERION -> hyperion(plugin);
            case BREAKER -> breaker(plugin, MAX_CHARGES);
            case TERMINATOR -> terminator(plugin);
            case SCEPTRE -> sceptre(plugin);
            default -> {
                ItemStack map = new ItemStack(Material.FILLED_MAP);
                ItemMeta meta = map.getItemMeta();
                meta.displayName(Component.text("Dungeon Map", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(Component.text("Handed out when the run starts.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
                meta.getPersistentDataContainer().set(DungeonSecrets.itemTag(plugin), PersistentDataType.BYTE, (byte) 4);
                map.setItemMeta(meta);
                yield map;
            }
        };
    }

    private static void put(PlayerInventory inv, ItemStack stack) {
        for (int slot = 0; slot < 8; slot++) {
            ItemStack at = inv.getItem(slot);
            if (at == null || at.isEmpty()) {
                inv.setItem(slot, stack);
                return;
            }
        }
        inv.addItem(stack);
    }

    static boolean hasCharge(Player player) {
        return charges.getOrDefault(player.getUniqueId(), MAX_CHARGES) > 0;
    }

    static boolean takeCharge(JavaPlugin plugin, Player player) {
        int have = charges.getOrDefault(player.getUniqueId(), MAX_CHARGES);
        if (have <= 0) return false;
        setCharges(plugin, player, have - 1);
        track.accept(player, "breaker");
        return true;
    }

    static void regenCharges(JavaPlugin plugin, Player player) {
        int have = charges.getOrDefault(player.getUniqueId(), MAX_CHARGES);
        if (have < MAX_CHARGES) setCharges(plugin, player, have + 1);
    }

    private static void setCharges(JavaPlugin plugin, Player player, int value) {
        charges.put(player.getUniqueId(), value);
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!isBreaker(plugin, stack)) continue;
            ItemMeta meta = stack.getItemMeta();
            meta.lore(breakerLore(value));
            stack.setItemMeta(meta);
            chargeBar(stack, value);
        }
    }

    static void useAotv(Player player) {
        if (!ready(player, 50)) return;
        if (player.isSneaking()) etherwarp(player);
        else if (instant(player, INSTANT_RANGE) != null) {
            track.accept(player, "teleports");
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        }
    }

    static void useHyperion(Player player) {
        if (!ready(player, HYPERION_COOLDOWN_MS)) return;
        track.accept(player, "hyperion");
        instant(player, WITHER_IMPACT_RANGE);
        Location at = player.getLocation();
        World world = player.getWorld();
        world.spawnParticle(Particle.EXPLOSION, at, 8, 2, 1, 2, 0);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
        for (Entity e : world.getNearbyEntities(at, IMPLOSION_RADIUS, IMPLOSION_RADIUS, IMPLOSION_RADIUS)) {
            if (!(e instanceof Bat) && !(e instanceof Monster)) continue;
            if (e.getLocation().distanceSquared(at) > IMPLOSION_RADIUS * IMPLOSION_RADIUS) continue;
            ((org.bukkit.entity.LivingEntity) e).damage(10_000, player);
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 2, false, false, false));
    }

    private static boolean ready(Player player, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) return false;
        lastUse.put(player.getUniqueId(), now);
        return true;
    }

    static java.util.function.Predicate<Location> noteleport = l -> false;
    static java.util.function.BiConsumer<Player, String> track = (p, k) -> { };
    private static Location instant(Player player, int range) {
        Location from = player.getLocation();
        if (noteleport.test(from)) return null;
        World world = player.getWorld();
        Vector eye = from.toVector().add(new Vector(0, player.isSneaking() ? EYE_SNEAKING : EYE, 0));
        Vector dir = from.getDirection().normalize();
        Vector last = null;
        boolean blocked = false;
        for (int i = 0; i <= range; i++) {
            Vector at = eye.clone().add(dir.clone().multiply(i));
            if (!clear(world, at, INSTANT_PASSABLE)) {
                blocked = true;
                break;
            }
            last = at;
        }
        if (blocked) player.sendMessage(Component.text("There are blocks in the way!", NamedTextColor.RED));
        if (last == null) return null;
        Location to = new Location(world, Math.floor(last.getX()) + 0.5, Math.floor(last.getY()), Math.floor(last.getZ()) + 0.5,
            from.getYaw(), from.getPitch());
        if (noteleport.test(to)) return null;
        Net.teleportKeepLook(player, to.getX(), to.getY(), to.getZ());
        player.setFallDistance(0);
        track.accept(player, "etherwarps");
        return to;
    }

    private static boolean clear(World world, Vector at, Set<Material> passable) {
        int x = (int) Math.floor(at.getX()), y = (int) Math.floor(at.getY()), z = (int) Math.floor(at.getZ());
        return passable.contains(world.getBlockAt(x, y, z).getType()) && passable.contains(world.getBlockAt(x, y + 1, z).getType());
    }

    private static void etherwarp(Player player) {
        World world = player.getWorld();
        Location from = player.getLocation();
        Vector start = from.toVector().add(new Vector(0, EYE_SNEAKING, 0));
        Vector end = start.clone().add(from.getDirection().normalize().multiply(ETHER_RANGE));
        int[] target = traverse(world, start, end);
        if (target == null) return;
        Location to = new Location(world, target[0] + 0.5, target[1] + 1.05, target[2] + 0.5, from.getYaw(), from.getPitch());
        if (noteleport.test(from) || noteleport.test(to)) return;
        world.spawnParticle(Particle.WITCH, from, 25, 0.25, 1.0, 0.25, 0, null, true);
        Net.teleportKeepLook(player, to.getX(), to.getY(), to.getZ());
        player.setFallDistance(0);
        world.playSound(to, Sound.ENTITY_ENDER_DRAGON_HURT, 1f, 0.53968254f);
    }

    private static boolean etherPassable(Block block) {
        Material type = block.getType();
        if (!ETHER_PASSABLE.contains(type)) return false;
        return switch (type) {
            case WATER, LAVA -> block.getBlockData() instanceof org.bukkit.block.data.Levelled liquid && liquid.getLevel() == 0;
            case REDSTONE_TORCH, REDSTONE_WALL_TORCH -> block.getBlockData() instanceof org.bukkit.block.data.Lightable torch && torch.isLit();
            default -> true;
        };
    }

    private static int[] traverse(World world, Vector start, Vector end) {
        double x0 = start.getX(), y0 = start.getY(), z0 = start.getZ();
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        int endX = (int) Math.floor(end.getX()), endY = (int) Math.floor(end.getY()), endZ = (int) Math.floor(end.getZ());
        double dx = end.getX() - x0, dy = end.getY() - y0, dz = end.getZ() - z0;
        int stepX = (int) Math.signum(dx), stepY = (int) Math.signum(dy), stepZ = (int) Math.signum(dz);
        double invX = dx != 0 ? 1 / dx : Double.MAX_VALUE, invY = dy != 0 ? 1 / dy : Double.MAX_VALUE, invZ = dz != 0 ? 1 / dz : Double.MAX_VALUE;
        double deltaX = Math.abs(invX * stepX), deltaY = Math.abs(invY * stepY), deltaZ = Math.abs(invZ * stepZ);
        double maxX = Math.abs((x + Math.max(stepX, 0) - x0) * invX);
        double maxY = Math.abs((y + Math.max(stepY, 0) - y0) * invY);
        double maxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invZ);
        for (int i = 0; i < 1000; i++) {
            if (!etherPassable(world.getBlockAt(x, y, z))) {
                boolean room = etherPassable(world.getBlockAt(x, y + 1, z)) && etherPassable(world.getBlockAt(x, y + 2, z));
                return room ? new int[]{x, y, z} : null;
            }
            if (x == endX && y == endY && z == endZ) return null;
            if (maxX <= maxY && maxX <= maxZ) { maxX += deltaX; x += stepX; }
            else if (maxY <= maxZ) { maxY += deltaY; y += stepY; }
            else { maxZ += deltaZ; z += stepZ; }
        }
        return null;
    }
}
