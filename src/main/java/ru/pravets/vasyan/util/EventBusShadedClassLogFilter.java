package ru.pravets.vasyan.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/**
 * Silences a specific, known-harmless log spam produced by Forge's
 * EventSubclassTransformer against the resilience4j/vavr classes shaded into
 * this mod's jar (Jar-in-Jar).
 *
 * <p>Mechanics: the transformer runs for every class and loads the class's
 * superclass through the CURRENT thread's context classloader. resilience4j's
 * {@code *Event} classes are first loaded on ForkJoinPool worker threads
 * (circuit-breaker callbacks), where the TCCL is the AppClassLoader that does
 * not see the shaded jar. The transform therefore logs "Could not find parent
 * ..." plus a ClassNotFoundException stack, catches the failure and returns
 * the class untouched - the library works fine, only the log suffers.</p>
 *
 * <p>EventBus 6.0.x has no opt-out (handlesClass is hardcoded), so we install
 * a NARROW filter on the {@code net.minecraftforge.eventbus.EventSubclassTransformer}
 * logger: it DENYs only the two messages whose subject is a shaded package
 * ({@code io.github.resilience4j}, {@code io.vavr}) and stays NEUTRAL for
 * everything else, so genuine event-bus errors still reach the log.</p>
 */
public final class EventBusShadedClassLogFilter extends AbstractFilter {

    /** Packages shaded by this mod whose CNFE transformer noise is suppressed. */
    private static final String[] SHADED_PREFIXES = {"io.github.resilience4j", "io.vavr"};

    private EventBusShadedClassLogFilter() {
        super(Filter.Result.DENY, Filter.Result.NEUTRAL);
    }

    /**
     * Installs the filter on the EventSubclassTransformer logger. Safe to
     * call once during mod construction; afterwards log4j routes all matching
     * events through {@link #match(String, Throwable)}.
     */
    public static void install() {
        try {
            var ctx = (org.apache.logging.log4j.core.LoggerContext)
                org.apache.logging.log4j.LogManager.getContext(false);
            var config = ctx.getConfiguration();
            LoggerConfig loggerConfig =
                config.getLoggerConfig("net.minecraftforge.eventbus.EventSubclassTransformer");
            loggerConfig.addFilter(new EventBusShadedClassLogFilter());
            ctx.updateLoggers();
        } catch (Throwable t) {
            // Never let a logging tweak break mod startup.
            org.slf4j.LoggerFactory.getLogger("VasyanMod").debug(
                "Could not install EventBus spam filter: {}", t.toString());
        }
    }

    @Override
    public Filter.Result filter(LogEvent event) {
        if (event == null || event.getLevel().isMoreSpecificThan(Level.WARN)) {
            return Filter.Result.NEUTRAL;
        }
        Message message = event.getMessage();
        return match(message != null ? message.getFormattedMessage() : null, event.getThrown());
    }

    @Override
    public Filter.Result filter(Logger logger, Level level, Marker marker, Message msg,
                                Throwable t) {
        return match(msg != null ? msg.getFormattedMessage() : null, t);
    }

    @Override
    public Filter.Result filter(Logger logger, Level level, Marker marker, Object msg,
                                Throwable t) {
        return match(msg != null ? msg.toString() : null, t);
    }

    /**
     * DENY only when the event clearly concerns a shaded class: either the
     * "Could not find parent" line naming a shaded package, or the generic
     * "An error occurred building event handler" line carrying a
     * ClassNotFoundException for a shaded class. Anything else: NEUTRAL.
     */
    private Filter.Result match(String message, Throwable thrown) {
        if (message != null && message.startsWith("Could not find parent")) {
            return isShaded(slashToDot(message)) ? Filter.Result.DENY : Filter.Result.NEUTRAL;
        }
        if (thrown instanceof ClassNotFoundException && isShaded(thrown.getMessage())) {
            return Filter.Result.DENY;
        }
        return Filter.Result.NEUTRAL;
    }

    private static String slashToDot(String message) {
        return message.replace('/', '.');
    }

    private static boolean isShaded(String text) {
        if (text == null) {
            return false;
        }
        for (String prefix : SHADED_PREFIXES) {
            if (text.contains(prefix)) {
                return true;
            }
        }
        return false;
    }
}
