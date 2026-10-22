package dev.pawfect.bloodcamp.dungeon;

import dev.pawfect.bloodcamp.gen.Seed;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class MapMenu implements Listener {
    static final int MAX_MAPS = 15;
    static final int MAX_PLAYERS = 5;

    private static final TextColor TITLE = TextColor.color(0xF2C14E);
    private static final TextColor LABEL = TextColor.color(0x9AA0A6);
    private static final TextColor VALUE = TextColor.color(0xF1F3F4);
    private static final TextColor DIM = TextColor.color(0x5F6368);
    private static final TextColor SEED = TextColor.color(0x6FD3F2);
    private static final TextColor GOOD = TextColor.color(0x7BD88F);
    private static final TextColor WARN = TextColor.color(0xF4B860);
    private static final TextColor BAD = TextColor.color(0xF2766B);
    private static final TextColor GOLD = TextColor.color(0xF7D774);

    private static final int[] SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24, 29, 30, 31, 32, 33};
    private static final int HEADER = 4;
    private static final int CREATE = 48;
    private static final int CLOSE = 50;
    private static final int LOADOUT = 46;

    private static final int RANDOM = 10;
    private static final int DAILY = 12;
    private static final int TYPED = 14;
    private static final int SPEED = 16;
    private static final int BACK = 22;
    private static final int REVEAL = 24;

    record MapInfo(int number, String world, Seed seed, List<String> players, State state, int buildPercent,
                   long elapsedMillis, int secretsFound, int secretsTotal) {}

    enum State { BUILDING, WAITING, RUNNING, FINISHED }

    private enum Page { BROWSE, CREATE }

    private static final class Holder implements InventoryHolder {
        final Page page;
        Inventory inventory;
        final Map<Integer, String> worlds = new HashMap<>();
        final Map<Integer, Seed> seeds = new HashMap<>();

        Holder(Page page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final DungeonManager manager;
    private final Times times;
    private final Map<UUID, Long> typing = new HashMap<>();
    private BukkitTask ticker;

    MapMenu(JavaPlugin plugin, DungeonManager manager, Times times) {
        this.plugin = plugin;
        this.manager = manager;
        this.times = times;
    }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20, 20);
    }

    void disable() {
        if (ticker != null) ticker.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        }
    }

    void open(Player player) {
        Holder holder = new Holder(Page.BROWSE);
        holder.inventory = Bukkit.createInventory(holder, 54, title("Dungeon Maps"));
        fillBrowse(holder);
        player.openInventory(holder.inventory);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    private void openCreate(Player player) {
        Holder holder = new Holder(Page.CREATE);
        holder.inventory = Bukkit.createInventory(holder, 27, title("Create a Map"));
        fillCreate(holder, player);
        player.openInventory(holder.inventory);
    }

    private static Component title(String text) {
        return Component.text(text, NamedTextColor.DARK_GRAY);
    }

    private void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h && h.page == Page.BROWSE) fillBrowse(h);
        }
        long now = System.currentTimeMillis();
        typing.entrySet().removeIf(e -> {
            if (now - e.getValue() < 60_000) return false;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.sendMessage(Component.text("Seed entry timed out.", DIM));
            return true;
        });
    }

    private void fillBrowse(Holder holder) {
        Inventory inv = holder.inventory;
        holder.worlds.clear();
        holder.seeds.clear();
        ItemStack border = filler(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack inner = filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < inv.getSize(); i++) {
            int row = i / 9, col = i % 9;
            boolean edge = row == 0 || row == 5 || col == 0 || col == 8;
            inv.setItem(i, edge ? border : inner);
        }

        List<MapInfo> maps = manager.maps();
        Map<Integer, MapInfo> byNumber = new HashMap<>();
        for (MapInfo m : maps) byNumber.put(m.number(), m);

        inv.setItem(HEADER, item(Material.WITHER_SKELETON_SKULL, Component.text("Dungeon Maps", TITLE, TextDecoration.BOLD), List.of(
            Component.empty(),
            line("Open maps", count(maps.size(), MAX_MAPS)),
            line("Players per map", Component.text("up to " + MAX_PLAYERS, VALUE)),
            Component.empty(),
            Component.text("Click a map to join it.", LABEL),
            Component.text("Right-click one to copy its seed.", LABEL))));

        for (int n = 1; n <= MAX_MAPS; n++) {
            int slot = SLOTS[n - 1];
            MapInfo m = byNumber.get(n);
            if (m == null) {
                inv.setItem(slot, item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text("Slot " + n, DIM), List.of(
                    Component.text("Empty", DIM),
                    Component.empty(),
                    action("Click to create a map here"))));
                continue;
            }
            holder.worlds.put(slot, m.world());
            holder.seeds.put(slot, m.seed());
            inv.setItem(slot, mapItem(m));
        }

        boolean full = maps.size() >= MAX_MAPS || manager.isBuilding();
        inv.setItem(CREATE, item(full ? Material.GRAY_DYE : Material.CARTOGRAPHY_TABLE,
            Component.text("Create Map", full ? DIM : GOOD, TextDecoration.BOLD),
            full
                ? List.of(Component.empty(), Component.text(maps.size() >= MAX_MAPS ? "All 15 slots are taken." : "A map is building, one moment.", BAD))
                : List.of(Component.empty(), Component.text("Set your speed, then roll a", LABEL), Component.text("seed, play the daily or", LABEL), Component.text("type one in.", LABEL), Component.empty(), action("Click to start"))));
        inv.setItem(CLOSE, item(Material.BARRIER, Component.text("Close", BAD), List.of()));
        inv.setItem(LOADOUT, item(Material.CHEST, Component.text("Loadout", TITLE, TextDecoration.BOLD), List.of(Component.empty(),
            Component.text("Pick which items you bring", LABEL), Component.text("and where they go in your", LABEL),
            Component.text("inventory. Used for runs", LABEL), Component.text("and practice rooms.", LABEL), Component.empty(), action("Click to edit"))));
    }

    private ItemStack mapItem(MapInfo m) {
        int count = m.players().size();
        boolean full = count >= MAX_PLAYERS;
        Material material = switch (m.state()) {
            case BUILDING -> Material.SCAFFOLDING;
            case WAITING -> Material.MAP;
            case RUNNING -> Material.CLOCK;
            case FINISHED -> Material.NETHER_STAR;
        };
        TextColor nameColor = switch (m.state()) {
            case BUILDING -> DIM;
            case WAITING -> GOOD;
            case RUNNING -> WARN;
            case FINISHED -> GOLD;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Slot " + m.number(), DIM));
        lore.add(Component.empty());
        lore.add(line("Seed", Component.text(m.seed().code(), SEED)));
        lore.add(line("Status", status(m)));
        lore.add(Component.empty());
        lore.add(line("Players", count(count, MAX_PLAYERS)));
        for (String name : m.players()) lore.add(Component.text("  ▪ ", DIM).append(Component.text(name, VALUE)));
        if (m.state() != State.BUILDING && m.secretsTotal() > 0) {
            lore.add(Component.empty());
            lore.add(line("Secrets", Component.text(m.secretsFound(), VALUE).append(Component.text("/" + m.secretsTotal(), LABEL))));
        }
        List<Times.Clear> best = times.top(Times.onSeed(m.seed().code(), m.seed().floor().name()), 1);
        if (!best.isEmpty()) {
            lore.add(line("Best on seed", Component.text(Times.format(best.getFirst().millis()), GOLD)
                .append(Component.text(" " + best.getFirst().name(), DIM))));
        }
        if (m.seed().code().equals(Seed.today().code())) lore.add(Component.text("Today's daily seed", TITLE));
        lore.add(Component.empty());
        if (m.state() == State.BUILDING) lore.add(Component.text("Building, hang on...", DIM));
        else if (full) lore.add(Component.text("Full", BAD, TextDecoration.BOLD));
        else lore.add(action("Click to join"));
        lore.add(Component.text("Right-click to copy the seed", DIM));

        ItemStack stack = item(material, Component.text(m.seed().code(), nameColor, TextDecoration.BOLD), lore);
        stack.setAmount(Math.max(1, count));
        return stack;
    }

    private static Component status(MapInfo m) {
        return switch (m.state()) {
            case BUILDING -> Component.text("Building " + m.buildPercent() + "%", DIM);
            case WAITING -> Component.text("Waiting at Mort", GOOD);
            case RUNNING -> Component.text("Running ", WARN).append(Component.text(Times.format(m.elapsedMillis()), VALUE));
            case FINISHED -> Component.text("Finished in ", GOLD).append(Component.text(Times.format(m.elapsedMillis()), VALUE));
        };
    }

    private void fillCreate(Holder holder, Player player) {
        Inventory inv = holder.inventory;
        ItemStack border = filler(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack inner = filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, i / 9 == 1 ? inner : border);
        inv.setItem(RANDOM, item(Material.ENDER_EYE, Component.text("Random Seed", GOOD, TextDecoration.BOLD), List.of(
            Component.empty(),
            Component.text("Skip the seed and roll", LABEL),
            Component.text("a fresh map.", LABEL),
            Component.empty(),
            action("Click to build"))));

        Seed daily = Seed.today();
        List<Times.Clear> dailyBest = times.top(Times.onSeed(daily.code(), Seed.FLOOR.name()), 1);
        List<Component> dailyLore = new ArrayList<>(List.of(
            Component.empty(),
            line("Seed", Component.text(daily.code(), SEED)),
            Component.text("Same map for everyone today.", LABEL),
            Component.text("New one at midnight UTC.", DIM)));
        if (!dailyBest.isEmpty()) {
            dailyLore.add(Component.empty());
            dailyLore.add(line("Best today", Component.text(Times.format(dailyBest.getFirst().millis()), GOLD)
                .append(Component.text(" " + dailyBest.getFirst().name(), DIM))));
        }
        dailyLore.add(Component.empty());
        dailyLore.add(action("Click to build"));
        inv.setItem(DAILY, item(Material.CLOCK, Component.text("Daily Seed", TITLE, TextDecoration.BOLD), dailyLore));

        inv.setItem(TYPED, item(Material.NAME_TAG, Component.text("Enter a Seed", SEED, TextDecoration.BOLD), List.of(
            Component.empty(),
            Component.text("Build a map someone shared,", LABEL),
            Component.text("like ", LABEL).append(Component.text("K3Q9Z", SEED)),
            Component.empty(),
            action("Click, then type it in chat"))));

        int speed = Speed.get(player);
        List<Component> speedLore = new ArrayList<>(List.of(
            Component.empty(),
            line("Speed", Component.text("✦" + speed, VALUE)),
            line("Walking", Component.text(Speed.walk(speed) + " b/s", VALUE)),
            line("Sprinting", Component.text(Speed.sprint(speed) + " b/s", VALUE)),
            Component.empty(),
            Component.text("Hypixel caps Speed at " + Speed.CAP + ",", LABEL),
            Component.text(Speed.MAX + " with Black Cat or Young Dragon.", LABEL),
            Component.text("Saved with your clears.", DIM),
            Component.empty(),
            action("Left +10, right -10"),
            action("Shift for 50")));
        ItemStack speedItem = item(Material.SUGAR, Component.text("Speed ", VALUE, TextDecoration.BOLD)
            .append(Component.text("✦" + speed, speed > Speed.CAP ? GOLD : VALUE)), speedLore);
        if (speed > Speed.CAP) speedItem.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        inv.setItem(SPEED, speedItem);

        boolean reveal = manager.revealsMap(player);
        ItemStack revealItem = item(reveal ? Material.FILLED_MAP : Material.MAP, Component.text("Reveal Map ", VALUE, TextDecoration.BOLD)
            .append(Component.text(reveal ? "On" : "Off", reveal ? GOOD : DIM)), List.of(
            Component.empty(),
            Component.text("Your dungeon map shows every", LABEL),
            Component.text("room from the start, for maps", LABEL),
            Component.text("you build.", LABEL),
            Component.empty(),
            action("Click to turn " + (reveal ? "off" : "on"))));
        if (reveal) revealItem.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        inv.setItem(REVEAL, revealItem);

        inv.setItem(BACK, item(Material.ARROW, Component.text("Back", LABEL), List.of()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (holder.page == Page.BROWSE) clickBrowse(player, holder, slot, event.getClick());
        else clickCreate(player, holder, slot, event.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private void clickBrowse(Player player, Holder holder, int slot, ClickType click) {
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == CREATE) {
            tryCreate(player);
            return;
        }
        if (slot == LOADOUT) {
            if (manager.inRun(player)) {
                deny(player, "Finish or leave your run first.");
                return;
            }
            manager.openLoadout(player);
            return;
        }
        Seed seed = holder.seeds.get(slot);
        if (seed == null) {
            for (int s : SLOTS) if (s == slot) tryCreate(player);
            return;
        }
        String world = holder.worlds.get(slot);
        if (click.isRightClick()) {
            player.closeInventory();
            player.sendMessage(Component.text("Seed ", LABEL).append(Component.text(seed.toString(), SEED)
                .clickEvent(ClickEvent.copyToClipboard(seed.toString()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy")))).append(Component.text(" (click it to copy)", DIM)));
            return;
        }
        String problem = world == null ? "That map is still building." : manager.joinMap(player, world);
        if (problem != null) {
            deny(player, problem);
            return;
        }
        player.closeInventory();
    }

    private void tryCreate(Player player) {
        String problem = createProblem();
        if (problem != null) {
            deny(player, problem);
            return;
        }
        click(player);
        openCreate(player);
    }

    private String createProblem() {
        if (manager.maps().size() >= MAX_MAPS) return "All 15 map slots are taken.";
        if (manager.isBuilding()) return "A map is building, try again in a moment.";
        return null;
    }

    private void clickCreate(Player player, Holder holder, int slot, ClickType clickType) {
        switch (slot) {
            case RANDOM -> {
                String problem = createProblem();
                if (problem != null) {
                    deny(player, problem);
                    return;
                }
                player.closeInventory();
                manager.generate(player, Seed.random(new dev.pawfect.bloodcamp.gen.Rng(System.nanoTime() ^ player.getUniqueId().getMostSignificantBits())));
            }
            case DAILY -> {
                String problem = createProblem();
                if (problem != null) {
                    deny(player, problem);
                    return;
                }
                player.closeInventory();
                manager.generate(player, Seed.today());
            }
            case SPEED -> {
                if (manager.inRun(player)) {
                    deny(player, "You can't change speed mid-run.");
                    return;
                }
                int step = clickType.isShiftClick() ? 50 : 10;
                int now = Speed.get(player);
                int next = Speed.set(player, now + (clickType.isRightClick() ? -step : step));
                if (next == now) {
                    deny(player, next >= Speed.MAX ? "Speed tops out at " + Speed.MAX + "." : "Speed can't go below " + Speed.MIN + ".");
                    return;
                }
                manager.speedChanged(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f + next / 500f);
                fillCreate(holder, player);
            }
            case TYPED -> {
                player.closeInventory();
                typing.put(player.getUniqueId(), System.currentTimeMillis());
                player.sendMessage(Component.empty());
                player.sendMessage(Component.text("Type a seed in chat", TITLE, TextDecoration.BOLD)
                    .append(Component.text("  like K3Q9Z", DIM).decoration(TextDecoration.BOLD, false)));
                player.sendMessage(Component.text("[Cancel]", BAD).clickEvent(ClickEvent.runCommand("/bc cancelseed"))
                    .hoverEvent(HoverEvent.showText(Component.text("Stop typing a seed"))));
                player.sendMessage(Component.empty());
            }
            case REVEAL -> {
                manager.setRevealsMap(player, !manager.revealsMap(player));
                click(player);
                fillCreate(holder, player);
            }
            case BACK -> {
                click(player);
                open(player);
            }
            default -> { }
        }
    }

    boolean cancelTyping(Player player) {
        if (typing.remove(player.getUniqueId()) == null) return false;
        player.sendMessage(Component.text("Seed entry cancelled.", DIM));
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!typing.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> typed(player, text));
    }

    private void typed(Player player, String text) {
        if (!typing.containsKey(player.getUniqueId())) return;
        if (text.equalsIgnoreCase("cancel")) {
            cancelTyping(player);
            return;
        }
        Seed seed;
        try {
            seed = Seed.parse(text);
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text(e.getMessage(), BAD).append(Component.text(" Try again or type cancel.", DIM)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            return;
        }
        typing.remove(player.getUniqueId());
        String problem = createProblem();
        if (problem != null) {
            deny(player, problem);
            return;
        }
        manager.generate(player, seed);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        typing.remove(event.getPlayer().getUniqueId());
    }

    private static void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    }

    private static void deny(Player player, String message) {
        player.sendMessage(Component.text(message, BAD));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
    }

    private static Component line(String label, Component value) {
        return Component.text(label + ": ", LABEL).append(value);
    }

    private static Component count(int have, int max) {
        TextColor c = have >= max ? BAD : have == 0 ? LABEL : GOOD;
        return Component.text(have, c).append(Component.text("/" + max, LABEL));
    }

    private static Component action(String text) {
        return Component.text("▸ ", TITLE).append(Component.text(text, TITLE));
    }

    private static ItemStack filler(Material material) {
        ItemStack stack = new ItemStack(material);
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return stack;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain(name));
        meta.lore(lore.stream().map(MapMenu::plain).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
