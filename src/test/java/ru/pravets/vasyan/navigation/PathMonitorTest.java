package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pure unit tests for the {@link PathMonitor} stall/replan/fallback FSM.
 * Only uses {@link BlockPos}, which is safe outside the Minecraft bootstrap
 * (same precedent as {@code VasyanGoalTest} and {@code PathBudgetsTest}).
 */
class PathMonitorTest {

    private static final BlockPos BOT = new BlockPos(0, 64, 0);
    private static final BlockPos TARGET = new BlockPos(50, 64, 50); // outside near-range 2 of BOT

    private static PathMonitor monitor(int stallTicks, int maxReplans) {
        return new PathMonitor(VasyanGoal.near(TARGET, 2), stallTicks, maxReplans);
    }

    /** One stalled tick with navigation running and all capabilities available. */
    private static PathMonitor.Decision tick(PathMonitor m) {
        return m.onTick(BOT, false, true, true, true);
    }

    /**
     * Drives stalled ticks (navigation running, given capabilities) until {@code expected} is
     * emitted. Every intermediate decision must be CONTINUE; bounded loop guards against a
     * monitor that spins forever without escalating.
     */
    private static void stallUntil(PathMonitor m, PathMonitor.Decision expected, boolean canDig, boolean canPlace) {
        for (int i = 0; i < 10_000; i++) {
            PathMonitor.Decision d = m.onTick(BOT, false, true, canDig, canPlace);
            if (d == expected) {
                return;
            }
            assertEquals(PathMonitor.Decision.CONTINUE, d, "unexpected decision while waiting for " + expected);
        }
        fail("decision " + expected + " was never emitted within 10000 ticks");
    }

    @Test
    void progressReportsResetTheStallWindowSoDecisionStaysContinue() {
        var m = monitor(40, 3);

        for (int i = 0; i < 200; i++) {
            assertEquals(PathMonitor.Decision.CONTINUE, tick(m), "tick " + i);
            m.onProgress();
        }
    }

    @Test
    void fortyStalledTicksProduceReplanThenAFreshWindow() {
        var m = monitor(40, 3);

        for (int i = 1; i <= 39; i++) {
            assertEquals(PathMonitor.Decision.CONTINUE, tick(m), "tick " + i);
        }
        assertEquals(PathMonitor.Decision.REPLAN, tick(m), "tick 40 must escalate to REPLAN");

        // After a replan the window restarts: another full stall window before the next REPLAN.
        for (int i = 1; i <= 39; i++) {
            assertEquals(PathMonitor.Decision.CONTINUE, tick(m), "tick " + i);
        }
        assertEquals(PathMonitor.Decision.REPLAN, tick(m));
    }

    @Test
    void whenReplansAreExhaustedTheLadderStartsWithDigThrough() {
        var m = monitor(40, 3);

        for (int r = 0; r < 3; r++) {
            stallUntil(m, PathMonitor.Decision.REPLAN, true, true);
        }
        // Main replan budget is spent (maxReplans=3): the next stall escalation digs.
        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        assertFalse(m.finished(), "digging is still a recoverable state");
    }

    @Test
    void digThroughWithNoProgressForStallTicksAdvancesToPlaceScaffold() {
        var m = monitor(40, 0); // zero replan budget: straight to the fallback ladder

        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        // Dig gets its own grace window of stallTicks; only silence for the whole window advances.
        for (int i = 1; i <= 39; i++) {
            assertEquals(PathMonitor.Decision.CONTINUE, tick(m), "grace tick " + i);
        }
        assertEquals(PathMonitor.Decision.PLACE_SCAFFOLD, tick(m));
    }

    @Test
    void placeScaffoldWithNoProgressForStallTicksAdvancesToHopTeleport() {
        var m = monitor(40, 0);

        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        stallUntil(m, PathMonitor.Decision.PLACE_SCAFFOLD, true, true);
        stallUntil(m, PathMonitor.Decision.HOP_TELEPORT, true, true);
    }

    @Test
    void hopTeleportAttemptWithoutResultGivesUpButFlagIsPerInstance() {
        var m = monitor(40, 0);

        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        stallUntil(m, PathMonitor.Decision.PLACE_SCAFFOLD, true, true);
        stallUntil(m, PathMonitor.Decision.HOP_TELEPORT, true, true);
        assertFalse(m.finished(), "the teleport attempt itself is not a failure");

        // No progress within stallTicks after the teleport: last ladder step is GIVE_UP.
        stallUntil(m, PathMonitor.Decision.GIVE_UP, true, true);
        assertTrue(m.finished());
        assertEquals(PathMonitor.Decision.GIVE_UP, tick(m), "a finished monitor keeps giving up");

        // The once-flag lives per instance: a fresh monitor can teleport again.
        var fresh = monitor(40, 0);
        stallUntil(fresh, PathMonitor.Decision.DIG_THROUGH, true, true);
        stallUntil(fresh, PathMonitor.Decision.PLACE_SCAFFOLD, true, true);
        stallUntil(fresh, PathMonitor.Decision.HOP_TELEPORT, true, true);
    }

    @Test
    void navDoneOutsideGoalReplansImmediatelyOnItsOwnBudget() {
        var m = monitor(40, 3);

        for (int i = 0; i < 10; i++) {
            assertEquals(PathMonitor.Decision.REPLAN, m.onTick(BOT, true, false, true, true),
                    "navDone replan #" + (i + 1) + " must fire immediately, no stall needed");
        }
        assertEquals(PathMonitor.Decision.GIVE_UP, m.onTick(BOT, true, false, true, true),
                "11th navDone-outside-goal exhausts navDoneReplans");
        assertTrue(m.finished());
    }

    @Test
    void navDoneReplansDoNotConsumeTheMainReplanBudget() {
        var m = monitor(40, 3);

        for (int i = 0; i < 5; i++) {
            assertEquals(PathMonitor.Decision.REPLAN, m.onTick(BOT, true, false, true, true));
        }
        // Unstable navigation burned none of the 3 main replans: full stall budget still available.
        for (int r = 0; r < 3; r++) {
            stallUntil(m, PathMonitor.Decision.REPLAN, true, true);
        }
        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        assertFalse(m.finished());
    }

    @Test
    void cannotDigSkipsStraightToPlaceScaffold() {
        var m = monitor(40, 0);

        // canDig=false at ladder entry: DIG_THROUGH must never surface.
        stallUntil(m, PathMonitor.Decision.PLACE_SCAFFOLD, false, true);
    }

    @Test
    void cannotDigAndCannotPlaceSkipStraightToHopTeleport() {
        var m = monitor(40, 0);

        stallUntil(m, PathMonitor.Decision.HOP_TELEPORT, false, false);
    }

    @Test
    void cannotPlaceSkipsScaffoldWhenDigWindowExpires() {
        var m = monitor(40, 0);

        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, false);
        // Place is impossible: the next ladder step after dig is the hop teleport.
        stallUntil(m, PathMonitor.Decision.HOP_TELEPORT, true, false);
    }

    @Test
    void teleportOncePerInstanceThenGiveUpEvenIfCapabilitiesReturn() {
        var m = monitor(40, 0);

        stallUntil(m, PathMonitor.Decision.HOP_TELEPORT, false, false);
        // Capabilities restored mid-window must not resurrect the ladder: next step is GIVE_UP.
        stallUntil(m, PathMonitor.Decision.GIVE_UP, true, true);
        assertTrue(m.finished());
    }

    @Test
    void finishedMonitorStaysGivenUpRegardlessOfLaterInputs() {
        var m = monitor(40, 0);

        stallUntil(m, PathMonitor.Decision.HOP_TELEPORT, false, false);
        stallUntil(m, PathMonitor.Decision.GIVE_UP, false, false);
        for (int i = 0; i < 5; i++) {
            assertEquals(PathMonitor.Decision.GIVE_UP, m.onTick(BOT, true, true, true, true), "call " + (i + 1));
        }
    }

    @Test
    void reachingTheGoalReturnsContinueAndResetsTheStallWindow() {
        var m = monitor(40, 3);

        for (int i = 1; i <= 39; i++) {
            assertEquals(PathMonitor.Decision.CONTINUE, tick(m));
        }
        // Bot arrives: no escalation on the edge, and the window restarts cleanly afterwards.
        assertEquals(PathMonitor.Decision.CONTINUE, m.onTick(TARGET, false, true, true, true));
        for (int i = 1; i <= 39; i++) {
            assertEquals(PathMonitor.Decision.CONTINUE, tick(m));
        }
        assertEquals(PathMonitor.Decision.REPLAN, tick(m));
    }

    @Test
    void accessorsDefaultsAndValidation() {
        assertEquals(40, PathMonitor.DEFAULT_STALL_TICKS);
        assertEquals(3, PathMonitor.DEFAULT_MAX_REPLANS);
        assertEquals(10, PathMonitor.DEFAULT_NAV_DONE_REPLANS);

        var goal = VasyanGoal.near(TARGET, 2);
        var withDefaults = new PathMonitor(goal);
        assertSame(goal, withDefaults.goal());
        assertEquals(PathMonitor.Decision.CONTINUE, tick(withDefaults));

        assertThrows(IllegalArgumentException.class, () -> new PathMonitor(null, 40, 3));
        assertThrows(IllegalArgumentException.class, () -> new PathMonitor(goal, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new PathMonitor(goal, 40, -1));
        assertThrows(IllegalArgumentException.class, () -> new PathMonitor(goal, 40, 3, -1));
    }
}
