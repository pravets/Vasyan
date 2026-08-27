package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Pure stall/replan/fallback state machine for bot navigation.
 *
 * <p>The action glue ticks this monitor once per server tick with the bot's position and a few
 * capability flags computed before the call (canDig = block in the way is diggable, canPlace =
 * inventory holds a scaffold block). In exchange it returns one {@link Decision}: keep going,
 * replan the path, or escalate down a fallback ladder (dig through, place scaffold, hop
 * teleport, give up).
 *
 * <p>Decisions are made from counters only - no world access, no static state - so the monitor
 * is unit-testable without the Minecraft bootstrap. The glue executes each decision and calls
 * {@link #onRecoverySuccess()} whenever a recovery action visibly succeeded; actual bot motion
 * is observed directly by {@link #onTick(BlockPos, boolean, boolean, boolean, boolean)}.
 *
 * <p>Ladder semantics: a capability flag of false skips that ladder step and falls through to
 * the next one (DIG_THROUGH needs canDig, PLACE_SCAFFOLD needs canPlace); each emitted ladder
 * step gets its own grace window of stallTicks, and silence for that whole window advances to
 * the next step; HOP_TELEPORT is emitted at most once per monitor instance.
 *
 * <p>Replan accounting: stalls consume maxReplans; navigation finishing outside the goal
 * triggers an immediate REPLAN on a separate navDoneReplans budget instead, so unstable
 * navigation never burns the main budget before the first real stall. Either budget exhausting
 * while the goal is unreached ends in GIVE_UP.
 */
public final class PathMonitor {

    /** Decisions the monitor hands to the action glue for execution. */
    public enum Decision {
        /** Everything is fine (progress happening or goal reached): keep executing. */
        CONTINUE,
        /** Stall detected or nav finished off-goal: rebuild the path. */
        REPLAN,
        /** Vertical recovery: prepare/take one safe staircase step downward. */
        DESCEND_STEP,
        /** Vertical recovery: prepare/take one safe staircase step upward. */
        ASCEND_STEP,
        /** Stall replans exhausted and digging is possible: break the block in the way. */
        DIG_THROUGH,
        /** Dig unavailable or exhausted and scaffolding possible: place a block underfoot. */
        PLACE_SCAFFOLD,
        /** Dig and scaffold unavailable or exhausted: one-shot teleport past the obstacle. */
        HOP_TELEPORT,
        /** All recovery steps failed or budgets spent: abort the move. */
        GIVE_UP
    }

    /** Default stalled ticks tolerated before escalation. */
    public static final int DEFAULT_STALL_TICKS = 40;
    /** Default number of stall-triggered replans before the fallback ladder starts. */
    public static final int DEFAULT_MAX_REPLANS = 3;
    /** Default number of immediate "nav done but goal not reached" replans. */
    public static final int DEFAULT_NAV_DONE_REPLANS = 10;
    /** Default navigation speed carried into replans (matches VasyanPathing.GROUND_SPEED). */
    public static final double DEFAULT_NAV_SPEED = 1.0;

    private final VasyanGoal goal;
    private final int stallTicks;
    private final int maxReplans;
    private final int navDoneReplans;
    private final double navSpeed;
    private final VerticalRecoverySettings verticalRecovery;
    private final boolean hopTeleportEnabled;

    private int stallCounter;
    /** Ticks spent waiting since the last navDone-triggered replan (pacing). */
    private int navDoneStallCounter;
    private int replansUsed;
    private int navDoneReplansUsed;
    /** Scaffold blocks placed by vertical recovery during this monitor's lifetime. */
    private int verticalScaffoldPlaced;
    /** Last observed bot cell; a change since then counts as forward motion. */
    private BlockPos lastStallPos;

    /**
     * Which ladder step the monitor currently sits on. LADDER_ENTRY means stall escalations
     * still emit REPLAN while the main budget lasts; every later value is an already-emitted
     * fallback step waiting out its own grace window.
     */
    private enum Step {
        LADDER_ENTRY, DESCEND, ASCEND, DIG_THROUGH, PLACE_SCAFFOLD, HOP_TELEPORT, DONE
    }

    private Step step = Step.LADDER_ENTRY;
    private boolean teleported;

    /**
     * Creates a monitor with default budgets (stall 40, replans 3, navDoneReplans 10).
     *
     * @param goal goal the bot is trying to reach
     */
    public PathMonitor(VasyanGoal goal) {
        this(goal, DEFAULT_STALL_TICKS, DEFAULT_MAX_REPLANS);
    }

    /**
     * Creates a monitor with explicit stall/replan budgets and default navDoneReplans.
     *
     * @param goal       goal the bot is trying to reach
     * @param stallTicks stalled ticks without progress tolerated before each escalation
     * @param maxReplans stall-triggered replans allowed before the fallback ladder starts
     */
    public PathMonitor(VasyanGoal goal, int stallTicks, int maxReplans) {
        this(goal, stallTicks, maxReplans, DEFAULT_NAV_DONE_REPLANS);
    }

    /**
     * Creates a monitor with fully explicit budgets.
     *
     * @param goal           goal the bot is trying to reach
     * @param stallTicks     stalled ticks without progress tolerated before each escalation
     * @param maxReplans     stall-triggered replans allowed before the fallback ladder starts
     * @param navDoneReplans immediate off-goal replans after navigation finishes early
     */
    public PathMonitor(VasyanGoal goal, int stallTicks, int maxReplans, int navDoneReplans) {
        this(goal, stallTicks, maxReplans, navDoneReplans, DEFAULT_NAV_SPEED);
    }

    /**
     * Creates a monitor with fully explicit budgets and a navigation speed.
     *
     * @param goal           goal the bot is trying to reach
     * @param stallTicks     stalled ticks without progress tolerated before each escalation
     * @param maxReplans     stall-triggered replans allowed before the fallback ladder starts
     * @param navDoneReplans immediate off-goal replans after navigation finishes early
     * @param navSpeed       speed replanning should steer at (so combat/follow keep their pace)
     */
    public PathMonitor(VasyanGoal goal, int stallTicks, int maxReplans, int navDoneReplans,
                       double navSpeed) {
        this(goal, stallTicks, maxReplans, navDoneReplans, navSpeed, VerticalRecoverySettings.DEFAULT);
    }

    /**
     * Creates a monitor with fully explicit budgets, navigation speed and vertical limits.
     */
    public PathMonitor(VasyanGoal goal, int stallTicks, int maxReplans, int navDoneReplans,
                       double navSpeed, VerticalRecoverySettings verticalRecovery) {
        this(goal, stallTicks, maxReplans, navDoneReplans, navSpeed, verticalRecovery, true);
    }

    /** Creates a monitor with fully explicit recovery capabilities. */
    public PathMonitor(VasyanGoal goal, int stallTicks, int maxReplans, int navDoneReplans,
                       double navSpeed, VerticalRecoverySettings verticalRecovery,
                       boolean hopTeleportEnabled) {
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }
        if (stallTicks <= 0) {
            throw new IllegalArgumentException("stallTicks must be positive: " + stallTicks);
        }
        if (maxReplans < 0) {
            throw new IllegalArgumentException("maxReplans must be non-negative: " + maxReplans);
        }
        if (navDoneReplans < 0) {
            throw new IllegalArgumentException("navDoneReplans must be non-negative: " + navDoneReplans);
        }
        if (navSpeed <= 0) {
            throw new IllegalArgumentException("navSpeed must be > 0: " + navSpeed);
        }
        if (verticalRecovery == null) {
            throw new IllegalArgumentException("verticalRecovery must not be null");
        }
        this.goal = goal;
        this.stallTicks = stallTicks;
        this.maxReplans = maxReplans;
        this.navDoneReplans = navDoneReplans;
        this.navSpeed = navSpeed;
        this.verticalRecovery = verticalRecovery;
        this.hopTeleportEnabled = hopTeleportEnabled;
    }

    /**
     * Feeds one tick of observation into the state machine and returns what the glue should
     * do. Capability flags are computed by the caller beforehand; a false flag skips the
     * matching ladder step entirely.
     *
     * @param botPos   current bot position
     * @param navDone  true when path navigation reports it is done
     * @param hasPath  true when the bot currently has a path assigned
     * @param canDig   true when the block in the way may be broken
     * @param canPlace true when the inventory holds a placeable scaffold block
     * @return decision for the glue to execute this tick
     */
    public Decision onTick(BlockPos botPos, boolean navDone, boolean hasPath,
                           boolean canDig, boolean canPlace) {
        if (step == Step.DONE) {
            return Decision.GIVE_UP;
        }
        if (goal.hasReached(botPos)) {
            resetWindow();
            return Decision.CONTINUE;
        }
        // Real forward motion is progress: a walking bot is never "stalled", no
        // matter how long it takes. Only a stationary bot accumulates the stall
        // window (otherwise a >2s walk would fire a replan every 40 ticks and then
        // a dig/scaffold/hop ladder on flat ground - review #39).
        //
        // Progress means HORIZONTAL motion (X or Z changes). Vertical-only cell
        // changes - water bobbing, current drift, being pushed - are not forward
        // progress and must not reset the window; otherwise a bot bobbing at a
        // shoreline would stall forever and navDone-replans could be starved.
        boolean horizontallyMoved = lastStallPos != null
            && (botPos.getX() != lastStallPos.getX() || botPos.getZ() != lastStallPos.getZ());
        lastStallPos = botPos;
        if (horizontallyMoved) {
            // Vertical staircase steps always move to a horizontal neighbour.
            // This reset is therefore the contract that re-arms DESCEND/ASCEND
            // for the following step; onRecoverySuccess() intentionally does
            // NOT do it, because preparation alone must preserve ladder order.
            step = Step.LADDER_ENTRY;
            resetWindow();
            return Decision.CONTINUE;
        }
        if (navDone && !hasPath) {
            return handleNavDoneOutsideGoal(canDig, canPlace, botPos);
        }
        if (++stallCounter < stallTicks) {
            return Decision.CONTINUE;
        }
        resetWindow();
        return escalate(canDig, canPlace, botPos);
    }

    /**
     * Reports actual forward motion (the bot moved, mined or placed). Resets the stall window
     * and returns the monitor to normal tracking; consumed budgets are not restored.
     */
    public void onProgress() {
        // Real motion happened: step back to normal tracking (a fresh stall
        // window, back at LADDER_ENTRY so the next decision is a vanilla replan
        // before any recovery step is re-tried). This prevents the ladder from
        // re-firing DIG_THROUGH on every window after a single successful dig.
        step = Step.LADDER_ENTRY;
        resetWindow();
    }

    /**
     * Reports that the current recovery action visibly succeeded (a block was dug, a scaffold
     * was placed, or the hop teleport happened) without claiming the bot moved. The step gets a
     * fresh grace window, but the ladder position is preserved: if the bot remains stationary,
     * the next stall window must ADVANCE the ladder instead of alternating mutually cancelling
     * recovery steps (e.g. place a scaffold ahead, then dig that same scaffold back down).
     */
    public void onRecoverySuccess() {
        resetWindow();
    }

    /** Records one vertical scaffold placement; glue calls only after a successful setBlock. */
    public void recordVerticalScaffoldPlacement() {
        verticalScaffoldPlaced++;
        onRecoverySuccess();
    }

    /** Whether vertical recovery may place another scaffold block under its configured cap. */
    public boolean canPlaceVerticalScaffold() {
        return verticalScaffoldPlaced < verticalRecovery.maxScaffoldBlocks();
    }

    /** Goal this monitor drives towards. */
    public VasyanGoal goal() {
        return goal;
    }

    /** Navigation speed replans should steer at (combat/follow keep their pace). */
    public double navSpeed() {
        return navSpeed;
    }

    /**
     * Whether the monitor has irrevocably given up. A finished monitor keeps answering ticks
     * with GIVE_UP.
     *
     * @return true once no further recovery step exists
     */
    public boolean finished() {
        return step == Step.DONE;
    }

    private void resetWindow() {
        stallCounter = 0;
        navDoneStallCounter = 0;
    }

    /**
     * Whether the monitor is currently executing the fallback ladder (dig/scaffold/teleport)
     * or has exhausted its paced replan budget. While true, callers with a think-budget should
     * keep ticking instead of failing: recovery steps take many stall windows by design.
     */
    public boolean inLadderRecovery() {
        return step != Step.LADDER_ENTRY || navDoneReplansUsed > 0;
    }

    private Decision handleNavDoneOutsideGoal(boolean canDig, boolean canPlace, BlockPos botPos) {
        // A finished path that ends off-goal replans at most once per stall
        // window: without this pacing the navDone branch burns all
        // navDoneReplans in consecutive ticks (each replan takes one tick to
        // fail again) and GIVE_UP fires before the stall ladder - dig/scaffold/
        // teleport - ever gets a chance (found by the wall-dig behavior test).
        if (++navDoneStallCounter < stallTicks) {
            return Decision.CONTINUE;
        }
        navDoneStallCounter = 0;
        if (navDoneReplansUsed < navDoneReplans) {
            // For a vertical target, one failed navDone REPLAN is enough: the
            // remaining paced horizontal replans cannot bridge the Y delta and
            // only waste hundreds of ticks on slow servers.
            if (navDoneReplansUsed > 0 && verticalDecision(botPos) != null) {
                navDoneReplansUsed = navDoneReplans;
            } else {
                navDoneReplansUsed++;
                return Decision.REPLAN;
            }
        }
        // Paced replans are exhausted, but the fallback ladder must still run:
        // hand control to the stall ladder starting at DIG_THROUGH (the main
        // replan budget is pre-spent so the ladder entry jumps straight past
        // further replans). GIVE_UP only after the ladder itself is exhausted.
        this.replansUsed = Math.max(replansUsed, maxReplans);
        resetWindow();
        return escalate(canDig, canPlace, botPos);
    }

    /**
     * Advances the fallback ladder and returns its decision, skipping steps whose capability
     * flag is false. Called only right after a full stall window elapsed without progress: the
     * step the monitor was sitting on just failed its grace window, so the monitor moves past
     * it; every newly entered step is emitted immediately (with a fresh window).
     */
    private Decision escalate(boolean canDig, boolean canPlace, BlockPos botPos) {
        boolean failed = step != Step.LADDER_ENTRY;
        while (true) {
            if (step == Step.LADDER_ENTRY) {
                if (replansUsed < maxReplans) {
                    replansUsed++;
                    return Decision.REPLAN;
                }
                Decision vertical = verticalDecision(botPos);
                if (vertical != null) {
                    step = vertical == Decision.DESCEND_STEP ? Step.DESCEND : Step.ASCEND;
                    failed = false;
                    return vertical;
                }
                step = Step.DIG_THROUGH;
                failed = false;
            } else if (step == Step.DESCEND || step == Step.ASCEND) {
                step = Step.DIG_THROUGH;
                failed = false;
            } else if (step == Step.DIG_THROUGH) {
                if (!canDig || failed) {
                    step = Step.PLACE_SCAFFOLD;
                    failed = false;
                } else {
                    return Decision.DIG_THROUGH;
                }
            } else if (step == Step.PLACE_SCAFFOLD) {
                if (!canPlace || failed) {
                    step = Step.HOP_TELEPORT;
                    failed = false;
                } else {
                    return Decision.PLACE_SCAFFOLD;
                }
            } else if (step == Step.HOP_TELEPORT) {
                if (!hopTeleportEnabled) {
                    finish();
                    return Decision.GIVE_UP;
                }
                if (!failed && !teleported) {
                    teleported = true;
                    return Decision.HOP_TELEPORT;
                }
                finish();
                return Decision.GIVE_UP;
            } else {
                return Decision.GIVE_UP;
            }
        }
    }

    /** Chooses vertical staircase recovery when the stalled goal is mainly a Y problem. */
    private @Nullable Decision verticalDecision(BlockPos botPos) {
        if (!verticalRecovery.enabled() || botPos == null) {
            return null;
        }
        BlockPos anchor = VasyanGoal.anchor(goal, botPos);
        int dy = anchor.getY() - botPos.getY();
        int horizontal = Math.max(Math.abs(anchor.getX() - botPos.getX()),
            Math.abs(anchor.getZ() - botPos.getZ()));
        if (dy == 0 || Math.abs(dy) > verticalRecovery.maxDistance()
                || (dy < 0 && horizontal > verticalRecovery.horizontalRange())) {
            return null;
        }
        return dy < 0 ? Decision.DESCEND_STEP : Decision.ASCEND_STEP;
    }

    private void finish() {
        step = Step.DONE;
        resetWindow();
    }
}
