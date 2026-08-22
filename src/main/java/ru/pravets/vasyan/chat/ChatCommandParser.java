package ru.pravets.vasyan.chat;

import java.util.List;
import java.util.Locale;

/**
 * Pure helpers for interpreting natural-language commands coming from the
 * K-panel chat. No Minecraft dependencies - unit-testable.
 */
public final class ChatCommandParser {

    /** Command prefixes that address ALL Vasyans (lowercase, with trailing space). */
    private static final List<String> ALL_PREFIXES = List.of(
        "all vasyans ", "all ", "everyone ", "everybody ",
        "все боты ", "всем ", "все "
    );

    /** First words that mean "stop / stay in place". */
    private static final List<String> STAY_WORDS = List.of(
        "stay", "stop", "wait", "freeze",
        "стой", "замри", "остановись", "стоять", "жди", "стоп"
    );

    /** Substrings that mean "gather until the inventory is full". */
    private static final List<String> FILL_MARKERS = List.of(
        "full inventory", "fill inventory", "fill", "until full", "до полного инвентаря",
        "полный инвентарь", "заполни инвентарь", "до упора", "по максимуму", "под завязку"
    );

    /** Whether the command asks the bot to gather until the inventory is full. */
    public static boolean isFillCommand(String lowerCommand) {
        if (lowerCommand == null) {
            return false;
        }
        String trimmed = lowerCommand.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty()) {
            return false;
        }
        return FILL_MARKERS.stream().anyMatch(trimmed::contains);
    }

    /** Substrings that mean "look around / what do you see". */
    private static final List<String> LOOK_MARKERS = List.of(
        "что ты видишь", "что видишь", "что ты тут видишь", "что вокруг",
        "look around", "what do you see", "what you see", "look"
    );

    /** Substrings that mean "one full stack of the resource" ("стак"). */
    private static final List<String> STACK_MARKERS = List.of("стак", "stack of", " stack");

    /** Whether the command asks for exactly one full stack of the resource. */
    public static boolean isStackCommand(String lowerCommand) {
        if (lowerCommand == null) {
            return false;
        }
        String trimmed = lowerCommand.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty()) {
            return false;
        }
        return STACK_MARKERS.stream().anyMatch(trimmed::contains);
    }

    /** Whether the command asks the bot to describe its surroundings. */
    public static boolean isLookCommand(String lowerCommand) {
        if (lowerCommand == null) {
            return false;
        }
        String trimmed = lowerCommand.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty()) {
            return false;
        }
        return LOOK_MARKERS.stream().anyMatch(trimmed::startsWith);
    }

    /** Whether the command asks for wood/trees in general (any log type). */
    public static boolean isWoodCommand(String lowerCommand) {
        if (lowerCommand == null) {
            return false;
        }
        String trimmed = lowerCommand.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty()) {
            return false;
        }
        for (String wood : WOOD_MARKERS) {
            if (trimmed.contains(wood)) {
                return true;
            }
        }
        return false;
    }

    private static final List<String> WOOD_MARKERS = List.of(
        "wood", "tree", "trees", "logs", "log", "timber",
        "дерев", "брев", "брёв", "лес", "дров"
    );

    private ChatCommandParser() {}

    /**
     * Whether the (already lowercased) command is addressed to all Vasyans:
     * "all teleport to me", "everyone come", "все телепортируйтесь ко мне", ...
     */
    public static boolean isAllCommand(String lowerCommand) {
        for (String prefix : ALL_PREFIXES) {
            if (lowerCommand.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the (already lowercased) command starts with a stay/stop word:
     * "stay", "stop", "wait here", "стой", "замри на месте", ...
     */
    public static boolean isStayCommand(String lowerCommand) {
        String trimmed = lowerCommand.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String firstWord = trimmed.split("\\s+")[0];
        return STAY_WORDS.contains(firstWord);
    }

    /** Convenience: lowercase with ROOT locale (locale-independent). */
    public static String normalize(String command) {
        return command.toLowerCase(Locale.ROOT);
    }

    /**
     * Removes the "all ..." addressing prefix from a command, e.g.
     * "all stay" / "все телепортируйтесь ко мне" -> "stay" / "телепортируйтесь ко мне".
     * Used when forwarding to the server via "tell all", so the payload the
     * Vasyans receive does not start with the addressing word.
     */
    public static String stripAllPrefix(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (String prefix : ALL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return command.substring(prefix.length()).trim();
            }
        }
        return command;
    }
}
