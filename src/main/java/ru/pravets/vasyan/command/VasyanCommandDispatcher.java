package ru.pravets.vasyan.command;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.chat.ChatCommandParser;
import ru.pravets.vasyan.chat.NameMatcher;
import ru.pravets.vasyan.debug.VasyanEnvironmentScanner;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dispatches a natural-language command to Vasyans, shared by the /vasyan tell
 * command (panel K) and voice commands.
 *
 * <p>Addressing rules (in order):
 * <ol>
 *   <li>command starts with a bot name ("alex ...", "Алекс ...") - the name is
 *       matched via {@link NameMatcher} (transliteration/dictionary aware) and
 *       stripped from the command;</li>
 *   <li>command is an all-command ("all ...", "все ...") - every Vasyan;</li>
 *   <li>otherwise - the Vasyan nearest to the speaker (same dimension only).</li>
 * </ol>
 */
public final class VasyanCommandDispatcher {

    /** Shared executor for LLM command processing (bounded, daemon). */
    private static final ExecutorService COMMAND_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "vasyan-command");
        t.setDaemon(true);
        return t;
    });

    private VasyanCommandDispatcher() {}

    /** Returns how many Vasyans received the command. */
    public static int dispatch(CommandSourceStack source, String command) {
        VasyanManager manager = VasyanMod.getVasyanManager();
        List<String> names = manager.getVasyanNames();
        if (names.isEmpty()) {
            source.sendFailure(Component.literal("§cNo Vasyans spawned. Use /vasyan spawn <name>"));
            return 0;
        }

        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        String lower = ChatCommandParser.normalize(trimmed);

        // 1. bot name prefix ("alex ...", "алекс ...")
        String firstWord = trimmed.split("\\s+", 2)[0];
        String matched = NameMatcher.matchName(firstWord, names);
        if (matched != null) {
            VasyanEntity vasyan = manager.getVasyan(matched);
            if (vasyan != null) {
                String rest = trimmed.substring(firstWord.length()).trim();
                deliver(vasyan, rest.isEmpty() ? trimmed : rest, source);
                return 1;
            }
        }

        // 2. all-command
        if (ChatCommandParser.isAllCommand(lower)) {
            int count = 0;
            for (String name : names) {
                VasyanEntity vasyan = manager.getVasyan(name);
                if (vasyan != null) {
                    deliver(vasyan, trimmed, source);
                    count++;
                }
            }
            final int sent = count;
            source.sendSuccess(() -> Component.literal("§7Command sent to " + sent + " Vasyan(s)"), false);
            return count;
        }

        // 3. nearest Vasyan to the speaker (same dimension)
        VasyanEntity nearest = nearestVasyan(source, manager);
        if (nearest != null) {
            deliver(nearest, trimmed, source);
            return 1;
        }
        return 0;
    }

    /**
     * Delivers a chat command to one Vasyan. Stay/stop commands are handled
     * deterministically (no LLM round-trip): the current action is cancelled,
     * navigation stops, and the Vasyan stays in place until the next command.
     */
    private static void deliver(VasyanEntity vasyan, String command, CommandSourceStack source) {
        String lower = ChatCommandParser.normalize(command);
        if (ChatCommandParser.isStayCommand(lower)) {
            ActionExecutor executor = vasyan.getActionExecutor();
            executor.stopCurrentAction();
            executor.setStaying(true);
            vasyan.getNavigation().stop();
            vasyan.getMemory().clearTaskQueue();
            source.sendSuccess(() -> Component.literal("§7" + vasyan.getVasyanName() + " stopped"), false);
            return;
        }

        if (ChatCommandParser.isLookCommand(lower)) {
            String description = VasyanEnvironmentScanner.describe(VasyanEnvironmentScanner.scan(vasyan));
            vasyan.sendChatMessage(description);
            source.sendSuccess(() -> Component.literal("§7" + vasyan.getVasyanName() + " looks around"), false);
            return;
        }

        COMMAND_EXECUTOR.execute(() -> {
            try {
                vasyan.getActionExecutor().processNaturalLanguageCommand(command);
            } catch (Exception e) {
                VasyanMod.LOGGER.warn("Command processing failed for {}: {}", vasyan.getVasyanName(), e.toString());
            }
        });
    }

    private static VasyanEntity nearestVasyan(CommandSourceStack source, VasyanManager manager) {
        if (!(source.getEntity() instanceof ServerPlayer speaker)) {
            return null; // console: no nearest Vasyan
        }
        VasyanEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (VasyanEntity vasyan : manager.getAllVasyans()) {
            if (!vasyan.level().dimension().equals(speaker.level().dimension())) {
                continue; // cross-dimension bots are never "nearest"
            }
            double dist = vasyan.distanceToSqr(speaker);
            if (dist < best) {
                best = dist;
                nearest = vasyan;
            }
        }
        return nearest;
    }
}
