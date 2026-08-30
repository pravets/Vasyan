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
    void navDoneOutsideGoalStillConsumesReplansEvenAfterHorizontalMotion() {
        var m = monitor(40, 3);
        BlockPos moved = BOT.east();

        PathMonitor.Decision d = PathMonitor.Decision.CONTINUE;
        for (int i = 0; i < 50 && d == PathMonitor.Decision.CONTINUE; i++) {
            // Bot moved horizontally but navigation finished off-goal.
            d = m.onTick(moved, true, false, true, true);
        }
        assertEquals(PathMonitor.Decision.REPLAN, d,
            "navDone outside goal must consume a navDone replan, not be masked by horizontal motion");
    }

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
    void navDoneOutsideGoalReplansOnItsOwnPacedBudget() {
        var m = monitor(40, 3);

        // Paced: each navDone replan requires a full stall window (40 ticks).
        for (int i = 0; i < 10; i++) {
            for (int t = 1; t < 40; t++) {
                assertEquals(PathMonitor.Decision.CONTINUE, m.onTick(BOT, true, false, true, true),
                        "navDone pacing tick " + t + " of window #" + (i + 1) + " must CONTINUE");
            }
            assertEquals(PathMonitor.Decision.REPLAN, m.onTick(BOT, true, false, true, true),
                    "navDone replan #" + (i + 1) + " fires after the full stall window");
        }
        // The 11th window ends the paced replan budget and hands over to the
        // fallback ladder: with canDig=true the first ladder step is DIG_THROUGH.
        for (int t = 1; t < 40; t++) {
            assertEquals(PathMonitor.Decision.CONTINUE, m.onTick(BOT, true, false, true, true));
        }
        assertEquals(PathMonitor.Decision.DIG_THROUGH, m.onTick(BOT, true, false, true, true),
                "after navDoneReplans are exhausted the ladder starts with DIG_THROUGH");
        assertFalse(m.finished());
    }

    @Test
    void hopTeleportCanBeDisabled() {
        var goal = VasyanGoal.near(TARGET, 2);
        var m = new PathMonitor(goal, 1, 0, 0, 1.0,
            VerticalRecoverySettings.DEFAULT, false);

        assertEquals(PathMonitor.Decision.GIVE_UP,
            m.onTick(BOT, true, false, false, false),
            "with dig/place unavailable and teleport disabled the route must fail honestly");
        assertTrue(m.finished());
    }

    @Test
    void verticalGoalBelowUsesDescendStepBeforeDigging() {
        var goal = VasyanGoal.near(new BlockPos(2, 62, 2), 1);
        var settings = new VerticalRecoverySettings(true, 8, 6);
        var m = new PathMonitor(goal, 1, 0, 0, 1.0, settings);

        assertEquals(PathMonitor.Decision.DESCEND_STEP,
            m.onTick(BOT, true, false, true, true));
    }

    @Test
    void verticalGoalAboveUsesAscendStepBeforeDigging() {
        var goal = VasyanGoal.near(new BlockPos(2, 66, 2), 1);
        var settings = new VerticalRecoverySettings(true, 8, 6);
        var m = new PathMonitor(goal, 1, 0, 0, 1.0, settings);

        assertEquals(PathMonitor.Decision.ASCEND_STEP,
            m.onTick(BOT, true, false, true, true));
    }

    @Test
    void oneBlockLedgeUsesAscendStepBeforeDigging() {
        var goal = VasyanGoal.near(new BlockPos(2, 65, 2), 1);
        var settings = new VerticalRecoverySettings(true, 8, 6);
        var m = new PathMonitor(goal, 1, 0, 0, 1.0, settings);

        assertEquals(PathMonitor.Decision.ASCEND_STEP,
            m.onTick(BOT, true, false, true, true),
            "a one-cube pit is still a vertical trap and must not fall through to teleport");
    }

    @Test
    void distantUphillRouteUsesLocalAscendRecovery() {
        var goal = VasyanGoal.near(new BlockPos(20, 70, 20), 1);
        var settings = new VerticalRecoverySettings(true, 8, 6);
        var m = new PathMonitor(goal, 1, 0, 0, 1.0, settings);

        assertEquals(PathMonitor.Decision.ASCEND_STEP,
            m.onTick(BOT, true, false, true, true),
            "a bot trapped in a pit must climb locally even when the route target is far away");
    }

    @Test
    void verticalNavDoneUsesOneReplanThenStairs() {
        var goal = VasyanGoal.near(new BlockPos(2, 62, 2), 1);
        var settings = new VerticalRecoverySettings(true, 8, 6);
        var m = new PathMonitor(goal, 1, 0, 10, 1.0, settings);

        assertEquals(PathMonitor.Decision.REPLAN,
            m.onTick(BOT, true, false, true, true));
        assertEquals(PathMonitor.Decision.DESCEND_STEP,
            m.onTick(BOT, true, false, true, true),
            "vertical targets must not burn all ten paced navDone replans");
    }

    @Test
    void successfulVerticalMovementReArmsAnotherVerticalStep() {
        var goal = VasyanGoal.near(new BlockPos(2, 60, 2), 1);
        var settings = new VerticalRecoverySettings(true, 8, 6);
        var m = new PathMonitor(goal, 1, 0, 0, 1.0, settings);

        assertEquals(PathMonitor.Decision.DESCEND_STEP,
            m.onTick(BOT, true, false, true, true));
        BlockPos firstStep = BOT.offset(1, -1, 0);
        assertEquals(PathMonitor.Decision.CONTINUE,
            m.onTick(firstStep, false, true, true, true),
            "moving onto the staircase step is real progress and re-arms the ladder");
        assertEquals(PathMonitor.Decision.DESCEND_STEP,
            m.onTick(firstStep, true, false, true, true),
            "the next stalled route must be allowed to prepare the following step");
    }

    @Test
    void verticalRecoverySkipsFarOrDisabledGoals() {
        var farGoal = VasyanGoal.near(new BlockPos(20, 60, 20), 1);
        var settings = new VerticalRecoverySettings(true, 8, 6);
        var far = new PathMonitor(farGoal, 1, 0, 0, 1.0, settings);
        assertEquals(PathMonitor.Decision.DIG_THROUGH,
            far.onTick(BOT, true, false, true, true));

        var nearGoal = VasyanGoal.near(new BlockPos(2, 62, 2), 1);
        var disabled = new PathMonitor(nearGoal, 1, 0, 0, 1.0,
            new VerticalRecoverySettings(false, 8, 6));
        assertEquals(PathMonitor.Decision.DIG_THROUGH,
            disabled.onTick(BOT, true, false, true, true));
    }

    @Test
    void successfulRecoveryStepGetsAGraceWindowButDoesNotRestartTheLadder() {
        var m = monitor(40, 0);

        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        m.onRecoverySuccess(); // block really broke, but the bot has not moved yet

        // The recovery success resets the grace window; it must NOT send the
        // ladder back to entry. Otherwise place/dig can alternate forever when
        // scaffolding creates the very obstacle DIG_THROUGH removes next.
        for (int t = 1; t < 40; t++) {
            assertEquals(PathMonitor.Decision.CONTINUE, tick(m), "grace tick " + t);
        }
        assertEquals(PathMonitor.Decision.PLACE_SCAFFOLD, tick(m),
            "after a successful dig without motion the ladder must advance, not dig again");
    }

    @Test
    void navDoneReplansDoNotConsumeTheMainReplanBudget() {
        var m = monitor(40, 3);

        for (int i = 0; i < 5; i++) {
            for (int t = 1; t < 40; t++) {
                assertEquals(PathMonitor.Decision.CONTINUE, m.onTick(BOT, true, false, true, true));
            }
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
    void progressResetsNavDonePacingWindow() {
        var m = monitor(40, 3);

        for (int half = 0; half < 2; half++) {
            for (int t = 0; t < 20; t++) {
                m.onTick(BOT, true, false, true, true);
            }
            m.onProgress(); // real motion resets both windows
        }
        // The pacing window restarted: a full fresh window is needed before the replan.
        for (int t = 1; t < 40; t++) {
            assertEquals(PathMonitor.Decision.CONTINUE, m.onTick(BOT, true, false, true, true));
        }
        assertEquals(PathMonitor.Decision.REPLAN, m.onTick(BOT, true, false, true, true));
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
        assertEquals(4, PathMonitor.DEFAULT_MAX_DIG_THROUGH);

        var goal = VasyanGoal.near(TARGET, 2);
        var withDefaults = new PathMonitor(goal);
        assertSame(goal, withDefaults.goal());
        assertEquals(PathMonitor.Decision.CONTINUE, tick(withDefaults));

        assertThrows(IllegalArgumentException.class, () -> new PathMonitor(null, 40, 3));
        assertThrows(IllegalArgumentException.class, () -> new PathMonitor(goal, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new PathMonitor(goal, 40, -1));
        assertThrows(IllegalArgumentException.class, () -> new PathMonitor(goal, 40, 3, -1));
        assertThrows(IllegalArgumentException.class,
            () -> new PathMonitor(goal, 40, 3, 10, 1.0, VerticalRecoverySettings.DEFAULT, true, -1));
    }

    @Test
    void movingBotIsNeverStalled_NoEscalationNoMatterHowLong() {
        var m = monitor(40, 3);

        // A bot that keeps advancing must never accumulate a stall window: over
        // many ticks of forward motion output stays CONTINUE and never escalates
        // to a replan or the recovery ladder (regression for review #39).
        for (int x = 1; x <= 120; x++) {
            BlockPos pos = new BlockPos(x, 64, 0);
            assertEquals(PathMonitor.Decision.CONTINUE,
                m.onTick(pos, false, true, true, true), "moving at x=" + x);
        }
        assertFalse(m.finished());
    }

    @Test
    void stationaryBotStillStalls_AfterMovingWindowCloses() {
        var m = monitor(40, 3);

        // Advance monotonically (progress), then stop: stall accumulation starts
        // only once the bot stays in one cell.
        for (int x = 1; x <= 5; x++) {
            assertEquals(PathMonitor.Decision.CONTINUE,
                m.onTick(new BlockPos(x, 64, 0), false, true, true, true), "move at x=" + x);
        }
        for (int i = 1; i < 40; i++) {
            assertEquals(PathMonitor.Decision.CONTINUE,
                m.onTick(new BlockPos(5, 64, 0), false, true, true, true), "halt tick " + i);
        }
        assertEquals(PathMonitor.Decision.REPLAN,
            m.onTick(new BlockPos(5, 64, 0), false, true, true, true));
    }

    @Test
    void verticalOnlyBobbingDoesNotCountAsProgress_NavDoneStillReplans() {
        var m = monitor(40, 3);

        // A bot bobbing up/down in water (Y cycling between two cells) is NOT
        // moving forward: vertical-only cell changes must not reset the stall
        // window, so the navDone replan still fires on schedule.
        for (int i = 1; i < 40; i++) {
            BlockPos bob = new BlockPos(0, 64 + (i % 2), 0);
            assertEquals(PathMonitor.Decision.CONTINUE,
                m.onTick(bob, true, false, true, true), "bob tick " + i);
        }
        assertEquals(PathMonitor.Decision.REPLAN,
            m.onTick(new BlockPos(0, 64 + (41 % 2), 0), true, false, true, true),
            "vertical-only motion must not starve the navDone replan");
    }

    // ---- dig-through budget (anti-tunneling) ----

    @Test
    void digThroughBudgetStopsHorizontalTunnelingAcrossProgressResets() {
        // Regression for the stone-tunnel bug: dig, walk one block forward
        // (real motion re-arms the ladder), dig again... Without a per-monitor
        // dig budget this loop never ends. The budget caps TOTAL digs even
        // though every dig "succeeds" and the bot keeps moving.
        var m = new PathMonitor(VasyanGoal.near(TARGET, 2), 1, 0, 0, 1.0,
            VerticalRecoverySettings.DEFAULT, false, 2);

        BlockPos pos = BOT;
        int digs = 0;
        boolean gaveUp = false;
        for (int guard = 0; guard < 100 && !gaveUp; guard++) {
            PathMonitor.Decision d = m.onTick(pos, false, true, true, false);
            if (d == PathMonitor.Decision.CONTINUE) {
                continue;
            }
            if (d == PathMonitor.Decision.DIG_THROUGH) {
                digs++;
                m.onRecoverySuccess(); // block really broke
                pos = pos.east();      // bot advances: the ladder re-arms on real motion
                continue;
            }
            assertEquals(PathMonitor.Decision.GIVE_UP, d,
                "dig budget spent, canPlace=false, teleport disabled: GIVE_UP is the only honest end");
            gaveUp = true;
        }
        assertTrue(gaveUp, "the monitor must give up instead of tunneling forever");
        assertEquals(2, digs, "exactly the configured dig budget may be spent");
    }

    @Test
    void treeRouteLeafDigBudgetAllowsTwelveDigsBeforeEscalation() {
        // Mangrove canopies can be more than four blocks deep. Tree routes use
        // a dedicated leaf dig budget so clearing foliage does not immediately
        // hit the generic tunnel cap.
        var m = new PathMonitor(VasyanGoal.near(TARGET, 2), 1, 0, 0, 1.0,
            VerticalRecoverySettings.DEFAULT, false, 12);

        BlockPos pos = BOT;
        int digs = 0;
        boolean gaveUp = false;
        for (int guard = 0; guard < 200 && !gaveUp; guard++) {
            PathMonitor.Decision d = m.onTick(pos, false, true, true, false);
            if (d == PathMonitor.Decision.CONTINUE) {
                continue;
            }
            if (d == PathMonitor.Decision.DIG_THROUGH) {
                digs++;
                m.onRecoverySuccess();
                pos = pos.east();
                continue;
            }
            assertEquals(PathMonitor.Decision.GIVE_UP, d,
                "after 12 leaf digs the generic route must give up (no place/teleport)");
            gaveUp = true;
        }
        assertTrue(gaveUp, "the monitor must give up instead of tunneling forever");
        assertEquals(12, digs, "a leaf budget of 12 must allow exactly 12 digs");
    }

    @Test
    void thinWallIsStillDugThroughWithinTheDefaultBudget() {
        // Behavior scenario C contract: a 1-block wall is still dug through -
        // the dig budget only stops TUNNELS, not the first dig of a route.
        var m = monitor(1, 0);
        assertEquals(PathMonitor.Decision.DIG_THROUGH, m.onTick(BOT, false, true, true, false));
    }

    @Test
    void zeroDigBudgetSkipsDigThroughEntirely() {
        var m = new PathMonitor(VasyanGoal.near(TARGET, 2), 1, 0, 0, 1.0,
            VerticalRecoverySettings.DEFAULT, false, 0);

        assertEquals(PathMonitor.Decision.GIVE_UP,
            m.onTick(BOT, false, true, true, false),
            "maxDigThrough=0 must skip digging and (no place/teleport) give up honestly");
        assertTrue(m.finished());
    }

    @Test
    void retargetPreservesDigThroughBudgetAndResetsStallWindow() {
        var m = new PathMonitor(VasyanGoal.near(TARGET, 2), 1, 0, 0, 1.0,
            VerticalRecoverySettings.DEFAULT, false, 4);

        // Consume two dig-through budgets with real progress between digs.
        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        m.onRecoverySuccess();
        m.onProgress();
        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        m.onRecoverySuccess();
        m.onProgress();

        BlockPos newTarget = new BlockPos(60, 64, 60);
        m.retarget(newTarget);

        // After retarget the stall window and ladder are fresh, but only two digs remain.
        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        m.onRecoverySuccess();
        m.onProgress();
        stallUntil(m, PathMonitor.Decision.DIG_THROUGH, true, true);
        m.onRecoverySuccess();
        m.onProgress();

        // Third post-retarget dig attempt must be skipped because the budget is spent.
        stallUntil(m, PathMonitor.Decision.GIVE_UP, true, false);
        assertTrue(m.finished());
    }

    @Test
    void retargetPreservesPlacedSupportsAndTeleportFlag() {
        var placed = new BlockPos(1, 63, 1);
        var m = new PathMonitor(VasyanGoal.near(TARGET, 2), 1, 0, 0, 1.0,
            VerticalRecoverySettings.DEFAULT, true, 0);
        m.recordPlacedSupport(placed);

        // With maxDigThrough=0 the ladder skips DIG_THROUGH, hits HOP_TELEPORT and
        // consumes the one-shot teleport flag.
        stallUntil(m, PathMonitor.Decision.HOP_TELEPORT, false, false);

        BlockPos newTarget = new BlockPos(60, 64, 60);
        m.retarget(newTarget);

        assertTrue(m.placedSupports().contains(placed),
            "retarget must preserve placed support blocks");

        // The ladder restarts from entry, but the teleport flag is already spent, so
        // the second climb through the ladder ends in GIVE_UP.
        stallUntil(m, PathMonitor.Decision.GIVE_UP, false, false);
        assertTrue(m.finished());
    }
}
