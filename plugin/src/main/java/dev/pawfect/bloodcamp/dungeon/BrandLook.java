package dev.pawfect.bloodcamp.dungeon;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

final class BrandLook {
    static final TextColor BRAND = TextColor.color(0xE0303A);
    static final TextColor GOLD = TextColor.color(0xF7D774);
    static final TextColor LABEL = TextColor.color(0x9AA0A6);
    static final TextColor VALUE = TextColor.color(0xF1F3F4);
    static final TextColor DIM = TextColor.color(0x5F6368);

    private static final String[] HOLDERS = {"§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a", "§b", "§c", "§d", "§e"};

    private static final class Shown {
        Scoreboard board;
        final Component[] lines = new Component[HOLDERS.length];
        int count = -1;
        Component header, footer;
    }

    private final Map<UUID, Shown> shown = new HashMap<>();

    static Component pair(String label, String value) {
        return Component.text(label + "  ", LABEL).append(Component.text(value, VALUE));
    }

    void show(Player player, List<Component> lines, Component header, Component footer) {
        Shown s = shown.get(player.getUniqueId());
        if (s == null || player.getScoreboard() != s.board) {
            s = new Shown();
            s.board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective objective = s.board.registerNewObjective("bloodcamp", Criteria.DUMMY,
                Component.text("BLOODCAMP", BRAND, TextDecoration.BOLD));
            objective.numberFormat(NumberFormat.blank());
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (String holder : HOLDERS) s.board.registerNewTeam("line" + holder.charAt(1)).addEntry(holder);
            shown.put(player.getUniqueId(), s);
            player.setScoreboard(s.board);
        }
        Objective objective = s.board.getObjective("bloodcamp");
        int count = Math.min(lines.size(), HOLDERS.length);
        if (count != s.count && objective != null) {
            for (int i = 0; i < HOLDERS.length; i++) {
                if (i < count) objective.getScore(HOLDERS[i]).setScore(count - i);
                else s.board.resetScores(HOLDERS[i]);
            }
            s.count = count;
        }
        for (int i = 0; i < count; i++) {
            Component line = lines.get(i);
            if (line.equals(s.lines[i])) continue;
            s.lines[i] = line;
            Team team = s.board.getTeam("line" + HOLDERS[i].charAt(1));
            if (team != null) team.prefix(line);
        }
        if (!header.equals(s.header) || !footer.equals(s.footer)) {
            s.header = header;
            s.footer = footer;
            player.sendPlayerListHeaderAndFooter(header, footer);
        }
    }

    void forget(Player player) {
        shown.remove(player.getUniqueId());
    }
}
