package dev.pawfect.bloodcamp.dungeon;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class Loadout implements Listener {
    private static final TextColor TITLE = TextColor.color(0xF2C14E);
    private static final TextColor LABEL = TextColor.color(0x9AA0A6);
    private static final TextColor DIM = TextColor.color(0x5F6368);
    private static final TextColor GOOD = TextColor.color(0x7BD88F);
    private static final TextColor BAD = TextColor.color(0xF2766B);

    static final byte[] TAGS = {2, 5, 7, 9, 8, 6, 10, 4};
    private static final int[] DEFAULT_SLOTS = {0, 1, 2, 3, 4, 5, 6, 8};
    private static final byte[] ADDED_LATER = {10};

    private static final int HOME_START = 36, HOME_END = 44;
    private static final int INFO = 45, RESET = 47, SAVE = 49, CANCEL = 51;

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        boolean discard;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey key;
    private final Consumer<Player> onSaved;

    Loadout(JavaPlugin plugin, Consumer<Player> onSaved) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "loadout");
        this.onSaved = onSaved;
    }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    static Map<Byte, Integer> layout(JavaPlugin plugin, Player player) {
        Map<Byte, Integer> out = new LinkedHashMap<>();
        String saved = player.getPersistentDataContainer().get(new NamespacedKey(plugin, "loadout"), PersistentDataType.STRING);
        if (saved == null) {
            for (int i = 0; i < TAGS.length; i++) out.put(TAGS[i], DEFAULT_SLOTS[i]);
            return out;
        }
        java.util.Set<Byte> mentioned = new java.util.HashSet<>();
        if (!saved.isEmpty()) for (String part : saved.split(",")) {
            String[] kv = part.split(":");
            try {
                byte tag = Byte.parseByte(kv[0]);
                int slot = Integer.parseInt(kv[1]);
                mentioned.add(tag);
                if (slot >= 0 && slot < 36 && !out.containsValue(slot)) out.put(tag, slot);
            } catch (RuntimeException ignored) {
            }
        }
        for (byte tag : ADDED_LATER) {
            if (mentioned.contains(tag)) continue;
            for (int i = 0; i < TAGS.length; i++) {
                if (TAGS[i] == tag && !out.containsValue(DEFAULT_SLOTS[i])) out.put(tag, DEFAULT_SLOTS[i]);
            }
        }
        return out;
    }

    private static int toGui(int playerSlot) {
        return playerSlot < 9 ? 27 + playerSlot : playerSlot - 9;
    }

    private static int toPlayer(int guiSlot) {
        return guiSlot >= 27 ? guiSlot - 27 : guiSlot + 9;
    }

    void open(Player player) {
        Holder holder = new Holder();
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Loadout", NamedTextColor.DARK_GRAY));
        fill(holder.inventory, layout(plugin, player));
        player.openInventory(holder.inventory);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    private void fill(Inventory inv, Map<Byte, Integer> layout) {
        for (int i = 0; i < HOME_START; i++) inv.setItem(i, null);
        int home = HOME_START;
        for (int i = HOME_START; i <= HOME_END; i++) inv.setItem(i, null);
        for (byte tag : TAGS) {
            Integer slot = layout.get(tag);
            if (slot != null) inv.setItem(toGui(slot), DungeonItems.kitItem(plugin, tag));
            else inv.setItem(home++, DungeonItems.kitItem(plugin, tag));
        }
        ItemStack bar = filler();
        for (int i = INFO; i < 54; i++) inv.setItem(i, bar);
        inv.setItem(INFO, button(Material.BOOK, Component.text("How it works", TITLE, TextDecoration.BOLD),
            "Top 3 rows are your inventory,", "the 4th row is your hotbar.", "", "Row 5 is left at home, put", "anything there you don't want."));
        inv.setItem(RESET, button(Material.WATER_BUCKET, Component.text("Reset", LABEL, TextDecoration.BOLD), "Back to the default layout."));
        inv.setItem(SAVE, button(Material.LIME_DYE, Component.text("Save", GOOD, TextDecoration.BOLD), "Closing saves too.", "Used next time you get your kit."));
        inv.setItem(CANCEL, button(Material.BARRIER, Component.text("Cancel", BAD, TextDecoration.BOLD), "Close without saving."));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        if (top && slot >= INFO) {
            event.setCancelled(true);
            switch (slot) {
                case RESET -> {
                    returnCursor(event.getView().getTopInventory(), event.getView().getCursor());
                    event.getView().setCursor(null);
                    Map<Byte, Integer> defaults = new LinkedHashMap<>();
                    for (int i = 0; i < TAGS.length; i++) defaults.put(TAGS[i], DEFAULT_SLOTS[i]);
                    fill(holder.inventory, defaults);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
                }
                case SAVE -> player.closeInventory();
                case CANCEL -> {
                    holder.discard = true;
                    player.closeInventory();
                }
                default -> { }
            }
            return;
        }
        boolean allowed = top && event.getClick() == ClickType.LEFT && (event.getAction() == InventoryAction.PICKUP_ALL
            || event.getAction() == InventoryAction.PLACE_ALL || event.getAction() == InventoryAction.SWAP_WITH_CURSOR
            || event.getAction() == InventoryAction.NOTHING);
        if (!allowed) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) return;
        boolean single = event.getRawSlots().size() == 1 && event.getType() == DragType.EVEN && event.getRawSlots().iterator().next() < INFO;
        if (!single) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        Inventory inv = event.getView().getTopInventory();
        returnCursor(inv, event.getView().getCursor());
        event.getView().setCursor(null);
        if (holder.discard || !(event.getPlayer() instanceof Player player)) return;
        StringBuilder saved = new StringBuilder();
        int brought = 0;
        for (int gui = 0; gui < HOME_START; gui++) {
            byte tag = DungeonSecrets.tagOf(plugin, inv.getItem(gui));
            if (tag == 0) continue;
            if (!saved.isEmpty()) saved.append(',');
            saved.append(tag).append(':').append(toPlayer(gui));
            brought++;
        }
        for (int gui = HOME_START; gui <= HOME_END; gui++) {
            byte tag = DungeonSecrets.tagOf(plugin, inv.getItem(gui));
            if (tag == 0) continue;
            if (!saved.isEmpty()) saved.append(',');
            saved.append(tag).append(":-1");
        }
        player.getPersistentDataContainer().set(key, PersistentDataType.STRING, saved.toString());
        player.sendMessage(Component.text("Loadout saved", GOOD).append(Component.text(" (" + brought + "/" + TAGS.length + " items).", LABEL)));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
        onSaved.accept(player);
    }

    private static void returnCursor(Inventory inv, ItemStack cursor) {
        if (cursor == null || cursor.isEmpty()) return;
        for (int i = HOME_START; i <= HOME_END; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, cursor);
                return;
            }
        }
        for (int i = 0; i < HOME_START; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, cursor);
                return;
            }
        }
    }

    private static ItemStack filler() {
        ItemStack stack = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return stack;
    }

    private static ItemStack button(Material material, Component name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(lore).stream().map(l -> (Component) Component.text(l, DIM).decoration(TextDecoration.ITALIC, false)).toList());
        stack.setItemMeta(meta);
        return stack;
    }
}
