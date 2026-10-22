package dev.pawfect.bloodcamp.dungeon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

final class PuzzleQuiz extends Puzzle {
    private record Question(String text, String right, List<String> wrong) {}

    private static final int[][] PEDESTALS = {{20, 6}, {15, 9}, {10, 6}};
    private static final String[] LETTERS = {"ⓐ", "ⓑ", "ⓒ"};
    private static final int Y = 70;
    private static final String ORUO = "§4[STATUE] Oruo the Omniscient§r§f: ";

    private static final List<String> STATUSES = List.of("Stalker", "New Necromancer", "Apprentice Necromancer", "Professor",
        "Shaman Necromancer", "Master Necromancer", "Necromancer Lord", "The Wither Lords");

    private static final List<Question> QUESTIONS = new ArrayList<>();

    static {
        status("The Watcher", "Stalker");
        status("Bonzo", "New Necromancer");
        status("Scarf", "Apprentice Necromancer");
        status("The Professor", "Professor");
        status("Thorn", "Shaman Necromancer");
        status("Livid", "Master Necromancer");
        status("Sadan", "Necromancer Lord");
        status("Maxor, Storm, Goldor, and Necron", "The Wither Lords");
        q("Which brother is on the Spider's Den?", "Rick", "Pat", "Shaggy");
        q("What is the name of Rick's brother?", "Pat", "Shaggy", "Jamie");
        q("What is the name of the vendor in the Hub who sells stained glass?", "Wool Weaver", "Adventurer", "Builder");
        q("What is the name of the person that upgrades pets?", "Kat", "Bea", "Elle");
        q("What is the name of the lady of the Nether?", "Elle", "Kat", "Bea");
        q("Which villager in the Village gives you a Rogue Sword?", "Jamie", "Andrew", "Liam");
        q("Which of these enemies does not spawn in the Spider's Den?", "Wither Skeleton", "Splitter Spider", "Weaver Spider");
        q("Which of these monsters only spawns at night?", "Zombie Villager", "Lapis Zombie", "Redstone Pigman");
        q("Which of these is not a dragon in The End?", "Zoomer Dragon", "Wise Dragon", "Unstable Dragon");
        q("Which of these is not a dragon in The End?", "Holy Dragon", "Strong Dragon", "Young Dragon");
        q("Which of these is not a dragon in The End?", "Elder Dragon", "Old Dragon", "Protector Dragon");
    }

    private static void status(String who, String right) {
        List<String> wrong = new ArrayList<>(STATUSES);
        wrong.remove(right);
        QUESTIONS.add(new Question("What is the status of " + who + "?", right, wrong));
    }

    private static void q(String text, String right, String... wrong) {
        QUESTIONS.add(new Question(text, right, List.of(wrong)));
    }

    private final List<Question> asked = new ArrayList<>();
    private final TextDisplay[] labels = new TextDisplay[3];
    private int number, answer = -1;

    PuzzleQuiz(Puzzles host, World world, Frame frame, String room, String name, int roomIndex) {
        super(host, world, frame, room, name, roomIndex);
    }

    @Override
    void begin() {
        List<Question> pool = new ArrayList<>(QUESTIONS);
        Collections.shuffle(pool, random);
        asked.clear();
        for (Question q : pool) {
            if (asked.stream().noneMatch(a -> a.text().equals(q.text()))) asked.add(q);
            if (asked.size() == 3) break;
        }
        number = 0;
        for (int i = 0; i < 3; i++) {
            labels[i] = spawn(loc(PEDESTALS[i][0] + 0.5, Y + 1.4, PEDESTALS[i][1] + 0.5, 0f), TextDisplay.class, t -> {
                t.setBillboard(Display.Billboard.CENTER);
                t.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
                t.setShadowed(true);
            });
        }
        say(ORUO + "I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.");
        later(220, this::ask);
    }

    private void ask() {
        Question q = asked.get(number);
        List<String> choices = new ArrayList<>(q.wrong());
        Collections.shuffle(choices, random);
        choices = new ArrayList<>(choices.subList(0, 2));
        answer = random.nextInt(3);
        choices.add(answer, q.right());
        say("§r                     §cQuestion #" + (number + 1));
        String text = q.text();
        if (text.length() > 60) {
            int cut = text.lastIndexOf(' ', 60);
            say("§r  §6" + text.substring(0, cut));
            say("§r  §6" + text.substring(cut + 1));
        } else {
            say("§r  §6" + text);
        }
        for (int i = 0; i < 3; i++) {
            say("§r     §6 " + LETTERS[i] + " §a" + choices.get(i));
            labels[i].text(LegacyComponentSerializer.legacySection().deserialize("§6" + LETTERS[i] + " §a" + choices.get(i)));
        }
        for (Player p : world.getPlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1f);
    }

    @Override
    boolean click(Player player, Block block, boolean right) {
        if (block.getType() != Material.STONE_BUTTON) return false;
        int[] r = rel(block);
        if (r[1] != Y) return false;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(r[0] - PEDESTALS[i][0]) + Math.abs(r[2] - PEDESTALS[i][1]) != 1) continue;
            if (answer < 0) return true;
            player.playSound(block.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.6f, 1f);
            if (i != answer) {
                say(ORUO + player.getName() + " chose the wrong answer! I shall never forget this moment of misrememberance.");
                failed(player, "chose the wrong answer!");
                return true;
            }
            answer = -1;
            number++;
            for (TextDisplay label : labels) label.text(net.kyori.adventure.text.Component.empty());
            if (number == 3) {
                say(ORUO + player.getName() + " answered the final question correctly!");
                say(ORUO + "I bestow upon you all the power of a hundred years!");
                solved(player, "answered all of Oruo's questions!");
            } else {
                say(ORUO + player.getName() + " answered Question #" + number + " correctly!");
                say(ORUO + (number == 1 ? "2 questions left... Then you will have proven your worth to me!" : "One more question!"));
                later(100, this::ask);
            }
            return true;
        }
        return false;
    }
}
