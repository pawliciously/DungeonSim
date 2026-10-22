package dev.pawfect.bloodcamp.dungeon;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

final class HypixelLook {
    static final String BRAND = "Hypixel BungeeCord (Bloodcamp) <- Hygot";

    record RunState(String floorCode, boolean master, int floorNumber, String serverName, long elapsedMillis, boolean started,
                    int secretsFound, int secretsTotal, int openedRooms, int completedRooms, int clearedPercent,
                    List<String> puzzles, List<Player> players, int witherKeys, boolean bloodKey) {}

    private static final String[] HOLDERS = {"§y", "§x", "§w", "§v", "§u", "§t", "§s", "§q", "§p", "§j", "§i", "§h", "§g"};
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final String[] COLUMNS = {"A", "B", "C", "D"};
    private static final UUID[] TAB_IDS = new UUID[80];
    private static final String[] TAB_NAMES = new String[80];

    static {
        for (int i = 0; i < 80; i++) {
            TAB_NAMES[i] = "!" + COLUMNS[i / 20] + "-" + (char) ('a' + i % 20);
            TAB_IDS[i] = UUID.nameUUIDFromBytes(("bloodcamp-tab-" + TAB_NAMES[i]).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Shown {
        Scoreboard board;
        final String[] sidebar = new String[HOLDERS.length];
        final String[] tab = new String[80];
    }

    private final Map<UUID, Shown> shown = new HashMap<>();

    boolean showing(Player player) {
        return shown.containsKey(player.getUniqueId());
    }

    void show(Player player, RunState state) {
        if (showing(player)) return;
        Shown s = new Shown();
        shown.put(player.getUniqueId(), s);
        brand(player, BRAND);
        payload(player, "hypixel:hello", out -> {
            writeBool(out, true);
            writeVarInt(out, 0);
        });
        location(player, state.serverName(), true);

        s.board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = s.board.registerNewObjective("SBScoreboard", Criteria.DUMMY,
            Component.text("SKYBLOCK", NamedTextColor.YELLOW, TextDecoration.BOLD));
        objective.numberFormat(NumberFormat.blank());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (int i = 0; i < HOLDERS.length; i++) {
            Team team = s.board.registerNewTeam("team_" + (HOLDERS.length - i));
            team.addEntry(HOLDERS[i]);
            objective.getScore(HOLDERS[i]).setScore(HOLDERS.length - i);
        }
        player.setScoreboard(s.board);

        List<String> tab = tabLines(state);
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            s.tab[i] = tab.get(i);
            entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(TAB_IDS[i], new GameProfile(TAB_IDS[i], TAB_NAMES[i]), true, 1,
                GameType.SURVIVAL, vanilla(tab.get(i)), true, 0, null));
        }
        send(player, new ClientboundPlayerInfoUpdatePacket(EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), entries));
        player.sendPlayerListHeaderAndFooter(
            LEGACY.deserialize("§bYou are playing on §e§lMC.HYPIXEL.NET\n§r§s"),
            LEGACY.deserialize("\n§a§lActive Effects\n§7You have §e0 §7active effects. Use \"§6/effects§7\" to see them!\n§r§s\n"
                + "§6§lDungeon Buffs\n§7No Buffs active. Find them by exploring the Dungeon!\n§r§s\n§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET"));
        update(player, state);
    }

    void hide(Player player) {
        Shown s = shown.remove(player.getUniqueId());
        if (s == null) return;
        send(player, new ClientboundPlayerInfoRemovePacket(List.of(TAB_IDS)));
        player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        if (player.getScoreboard() == s.board) player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        brand(player, Bukkit.getName());
        location(player, "bloodcamp", false);
    }

    void newMap(Player player, RunState state) {
        location(player, state.serverName(), true);
        player.sendActionBar(Component.text("  Team Score: 0 (D)"));
    }

    void forget(Player player) {
        shown.remove(player.getUniqueId());
    }

    void update(Player player, RunState state) {
        Shown s = shown.get(player.getUniqueId());
        if (s == null) return;
        List<String> lines = sidebarLines(state);
        for (int i = 0; i < HOLDERS.length; i++) {
            if (lines.get(i).equals(s.sidebar[i])) continue;
            s.sidebar[i] = lines.get(i);
            Team team = s.board.getTeam("team_" + (HOLDERS.length - i));
            if (team != null) team.prefix(Component.text(lines.get(i)));
        }
        List<String> tab = tabLines(state);
        List<ClientboundPlayerInfoUpdatePacket.Entry> changed = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            if (tab.get(i).equals(s.tab[i])) continue;
            s.tab[i] = tab.get(i);
            changed.add(new ClientboundPlayerInfoUpdatePacket.Entry(TAB_IDS[i], new GameProfile(TAB_IDS[i], TAB_NAMES[i]), true, 1,
                GameType.SURVIVAL, vanilla(tab.get(i)), true, 0, null));
        }
        if (!changed.isEmpty()) {
            send(player, new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), changed));
        }
    }

    private static List<String> sidebarLines(RunState s) {
        String floor = s.floorCode();
        int cleared = s.clearedPercent();
        String clearedColor = cleared >= 100 ? "§a" : cleared > 0 ? "§e" : "§c";
        return List.of(
            "§7" + LocalDate.now().format(DATE) + " §8" + s.serverName().replace("mini", "m"),
            "  ",
            "§f Late Summer 19th",
            " §711:30pm",
            " §7 §cThe Catacombs §7(" + floor + ")",
            "      ",
            "§fKeys: §c■ " + (s.bloodKey() ? "§a✓" : "§c✗") + " §8§8■ §a" + s.witherKeys() + "x",
            "§fTime Elapsed: §a§a" + time(s.started() ? s.elapsedMillis() : 0),
            "§fCleared: " + clearedColor + cleared + "% §8§8(0)",
            "          ",
            s.players().size() <= 1 ? "§3§lSolo" : "§3§lParty",
            "            ",
            "§ewww.hypixel.net");
    }

    private static List<String> tabLines(RunState s) {
        List<String> a = new ArrayList<>();
        a.add("         §b§lParty §f(" + s.players().size() + ")");
        for (Player p : s.players()) a.add("§8[§b100§8] §6" + p.getName() + " §f(§d" + DungeonManager.dungeonClass(p) + " XXXV§f)");
        a.add(" Ultimate: §aReady");
        a.add(" Revive Stones: §c0");

        int pct = s.secretsTotal() == 0 ? 0 : Math.round(100f * s.secretsFound() / s.secretsTotal());
        List<String> b = List.of(
            "       §2§lPlayer Stats",
            "§a§lDowned: §7NONE",
            " Time: §eN/A",
            " Revive: §cN/A",
            "",
            "§a§lTeam Deaths: §f0",
            " Team Damage Dealt: §c0❤",
            " Team Healing Done: §c0❤",
            " Your Milestone: §e♦⓿",
            " §7(0% to ❶)",
            "",
            "§a§lDiscoveries: §f" + s.secretsFound(),
            " Secrets Found: §b" + s.secretsFound(),
            " Crypts: §60");

        List<String> c = new ArrayList<>(List.of(
            "       §3§lDungeon Stats",
            "§b§lDungeon: §7Catacombs",
            " Opened Rooms: §5" + s.openedRooms(),
            " Completed Rooms: §d" + s.completedRooms(),
            " Secrets Found: §e" + pct + "%",
            " Time: §6" + (s.started() ? time(s.elapsedMillis()) : "N/A"),
            "",
            "§b§lPuzzles: §f(" + s.puzzles().size() + ")"));
        for (String puzzle : s.puzzles()) c.add("§r " + puzzle);

        List<String> d = List.of(
            "       §6§lAccount Info",
            "§e§lProfile: §aBloodcamp",
            " Pet Sitter: §bN/A",
            " Bank: §60",
            " Interest: §eN/A",
            "",
            "§e§lSkills: §aCatacombs 50: §3MAX",
            " Speed: §f500",
            "",
            "§e§lEvent: §5Practice",
            " Ends In: §eNever");

        List<String> out = new ArrayList<>(80);
        for (List<String> column : List.of(a, b, c, d)) {
            for (int i = 0; i < 20; i++) out.add(i < column.size() ? column.get(i) : "");
        }
        return out;
    }

    static String time(long millis) {
        long secs = millis / 1000;
        long m = secs / 60, s = secs % 60;
        return m > 0 ? String.format("%02dm %02ds", m, s) : String.format("%02ds", s);
    }

    private static net.minecraft.network.chat.Component vanilla(String legacy) {
        Component text = legacy.isEmpty() ? Component.empty() : LEGACY.deserialize(legacy);
        return PaperAdventure.asVanilla(Component.text().append(text).decoration(TextDecoration.ITALIC, false).build());
    }

    private static void brand(Player player, String brand) {
        send(player, new ClientboundCustomPayloadPacket(new BrandPayload(brand)));
    }

    static void location(Player player, String serverName, boolean dungeon) {
        payload(player, "hyevent:location", out -> {
            writeBool(out, true);
            writeVarInt(out, 1);
            writeString(out, serverName);
            writeOptional(out, dungeon ? "SKYBLOCK" : null);
            writeOptional(out, null);
            writeOptional(out, dungeon ? "dungeon" : null);
            writeOptional(out, dungeon ? "Dungeon" : null);
        });
    }

    private interface Writer {
        void write(ByteArrayOutputStream out);
    }

    private static void payload(Player player, String channel, Writer writer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out);
        send(player, new ClientboundCustomPayloadPacket(new DiscardedPayload(Identifier.parse(channel), out.toByteArray())));
    }

    private static void send(Player player, net.minecraft.network.protocol.Packet<?> packet) {
        var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null) connection.send(packet);
    }

    private static void writeBool(ByteArrayOutputStream out, boolean value) {
        out.write(value ? 1 : 0);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeOptional(ByteArrayOutputStream out, String value) {
        writeBool(out, value != null);
        if (value != null) writeString(out, value);
    }
}
