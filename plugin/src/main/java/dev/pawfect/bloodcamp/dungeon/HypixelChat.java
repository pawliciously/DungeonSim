package dev.pawfect.bloodcamp.dungeon;

import dev.pawfect.bloodcamp.gen.Floor;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

final class HypixelChat {
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII"};
    private static final String BAR = "▬".repeat(64);

    private HypixelChat() {}

    private static Component plain(Component... parts) {
        return Component.text().append(parts).decoration(TextDecoration.ITALIC, false).build();
    }

    private static String floorName(Floor floor) {
        String name = floor.name();
        if (name.equals("E")) return "Entrance";
        return "Floor " + ROMAN[name.charAt(name.length() - 1) - '0'];
    }

    private static String catacombs(Floor floor) {
        return "The Catacombs";
    }

    static Component entered(String name, Floor floor) {
        Component root = Component.text("-----------------------------\n", NamedTextColor.BLUE, TextDecoration.STRIKETHROUGH);
        TextDecoration strike = TextDecoration.STRIKETHROUGH;
        return root.append(
            Component.text(name, NamedTextColor.GOLD).decoration(strike, false),
            Component.text(" ", NamedTextColor.WHITE).decoration(strike, false),
            Component.text("entered ", NamedTextColor.YELLOW).decoration(strike, false),
            Component.text(catacombs(floor), NamedTextColor.GREEN).decoration(strike, false),
            Component.text(", ", NamedTextColor.YELLOW).decoration(strike, false),
            Component.text(floorName(floor), NamedTextColor.YELLOW).decoration(strike, false),
            Component.text("!\n", NamedTextColor.YELLOW).decoration(strike, false),
            Component.text("-----------------------------", NamedTextColor.BLUE));
    }

    static Component mort(String line) {
        return Component.text().append(
            Component.text("[NPC] ", NamedTextColor.YELLOW),
            Component.text("Mort", NamedTextColor.AQUA),
            Component.text(": ", NamedTextColor.WHITE),
            Component.text(line)).build();
    }

    static Component witherDoor(String name) {
        return plain(
            Component.text("", NamedTextColor.GREEN),
            Component.text(name, NamedTextColor.GOLD),
            Component.text(" opened a ", NamedTextColor.GREEN),
            Component.text("WITHER ", NamedTextColor.DARK_GRAY, TextDecoration.BOLD),
            Component.text("door!", NamedTextColor.GREEN));
    }

    static List<Component> keyPickup(String name, boolean blood) {
        Component got = blood
            ? plain(Component.text(name, NamedTextColor.GOLD), Component.text(" ", NamedTextColor.WHITE), Component.text("has obtained ", NamedTextColor.YELLOW),
                Component.text("", NamedTextColor.GREEN), Component.text("", NamedTextColor.RED), Component.text("Blood Key", NamedTextColor.RED),
                Component.text("!", NamedTextColor.YELLOW))
            : plain(Component.text(name, NamedTextColor.GOLD), Component.text(" ", NamedTextColor.WHITE), Component.text("has obtained ", NamedTextColor.YELLOW),
                Component.text("", NamedTextColor.GREEN), Component.text("", NamedTextColor.GOLD), Component.text("Wither Key", NamedTextColor.DARK_GRAY),
                Component.text("!", NamedTextColor.YELLOW));
        Component hint = plain(
            Component.text("RIGHT CLICK ", NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text("on ", NamedTextColor.GRAY),
            Component.text(blood ? "the " : "a ", NamedTextColor.GRAY),
            blood ? Component.text("BLOOD DOOR", NamedTextColor.RED) : Component.text("WITHER ", NamedTextColor.DARK_GRAY),
            Component.text(blood ? "" : "door", NamedTextColor.GRAY),
            Component.text(" to open it. This key can only be used to open ", NamedTextColor.GRAY),
            Component.text("1", NamedTextColor.GREEN),
            Component.text(" door!", NamedTextColor.GRAY));
        return List.of(got, hint);
    }

    static List<Component> bloodDoor() {
        return List.of(
            plain(Component.text("The ", NamedTextColor.RED).append(Component.text("BLOOD DOOR").decorate(TextDecoration.BOLD)),
                Component.text(" has been opened!", NamedTextColor.RED)),
            plain(Component.text("A shiver runs down your spine...", NamedTextColor.DARK_PURPLE)),
            plain(Component.text("[BOSS] The Watcher", NamedTextColor.RED), Component.text(": Ah, we meet again. As I foresaw...", NamedTextColor.WHITE)));
    }

    static List<Component> end(Floor floor, int clearedPercent, int secretsPercent, int bonus) {
        int skill = 20 + 80 * clearedPercent / 100;
        int explore = 60 * clearedPercent / 100 + 40 * Math.min(100, secretsPercent) / 100;
        int score = skill + explore + 100 + bonus;
        String grade;
        NamedTextColor color;
        if (score >= 300) { grade = "S+"; color = NamedTextColor.GOLD; }
        else if (score >= 270) { grade = "S"; color = NamedTextColor.GOLD; }
        else if (score >= 230) { grade = "A"; color = NamedTextColor.DARK_PURPLE; }
        else if (score >= 160) { grade = "B"; color = NamedTextColor.YELLOW; }
        else if (score >= 100) { grade = "C"; color = NamedTextColor.BLUE; }
        else { grade = "D"; color = NamedTextColor.RED; }
        Component bar = plain(Component.text(BAR, NamedTextColor.GREEN, TextDecoration.BOLD));
        return List.of(
            bar,
            plain(Component.text("                        ", NamedTextColor.WHITE), Component.text(catacombs(floor) + " ", NamedTextColor.RED),
                Component.text("- ", NamedTextColor.DARK_GRAY), Component.text(floorName(floor), NamedTextColor.YELLOW)),
            Component.empty(),
            plain(Component.text("                            ", NamedTextColor.WHITE), Component.text("Team Score: ", NamedTextColor.WHITE),
                Component.text(score + " ", NamedTextColor.GREEN), Component.text("(", NamedTextColor.WHITE),
                Component.text(grade, color), Component.text(")", NamedTextColor.WHITE)),
            bar);
    }
}
