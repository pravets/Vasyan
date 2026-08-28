package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.memory.GlobalResourceMemory;
import ru.pravets.vasyan.memory.VisionScanner;
import ru.pravets.vasyan.navigation.PathBudgets;
import ru.pravets.vasyan.navigation.PathMonitor;
import ru.pravets.vasyan.navigation.VasyanGoal;
import ru.pravets.vasyan.navigation.VasyanPathing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource gathering by ROUTING, not tunnel-digging.
 *
 * <p>The Vasyan walks a spiral of look-out stations around the start point
 * (amphibious navigation: walks on land, swims across water - never flies),
 * scanning with vision at each station ({@link VisionScanner#findVisible})
 * and mining ONLY visible target blocks. No tunnels are ever dug.</p>
 *
 * <p><b>Whole-tree felling:</b> when a mined log has a log above it, the Vasyan
 * enters fell mode - it collects the whole connected log component (BFS) and
 * climbs the trunk on a nerd-pole of REAL blocks from its inventory, felling
 * every log (jungle 2x2s and modded giants included), then dismantles the
 * pillar on the way down. No landscape litter.</p>
 *
 * <p>Stops early when: enough blocks gathered, inventory full, search timed
 * out, the route is exhausted, or felling stalls (no progress for a while).</p>
 *
 * <p><b>Routing on PathMonitor:</b> the whole ROUTING phase is delegated to
 * {@link ru.pravets.vasyan.navigation.PathMonitor} + {@link ru.pravets.vasyan.navigation.VasyanPathing}
 * - replans, dig-through, scaffolding and hop-teleports are monitor decisions,
 * so this action keeps no local stall counters for movement anymore.</p>
 */
public class GatherResourceAction extends BaseAction {

    private static final double MINE_REACH_SQ = 5.0 * 5.0;
    private static final int MINE_STALL_TICKS = 60; // unreachable visible ore grace period

    private static final int FELL_MAX_HEIGHT = 64; // world height - pillar can reach any tree top
    private static final int FELL_STALL_TICKS = 60; // no progress -> give up
    private static final int FELL_MAX_LOGS = 200; // connected logs per tree (forest guard)
    private static final int FELL_WAIT_TICKS = 25; // vacuum pickup grace period for pillar material
    private static final int UNREACHABLE_TARGETS_LIMIT = 32;
    private static final int VERTICAL_TRAP_HORIZONTAL_RADIUS = 4;
    private static final int VERTICAL_TRAP_VERTICAL_RADIUS = 6;
    private static final int NEARBY_SCAN_RADIUS = 10; // cube scan around the bot (no line of sight)
    private static final int STATUS_INTERVAL = 40; // ticks between STATUS debug pings (20s @ 2TPS)
    private static final double PROGRESS_MOVE_DISTANCE_SQ = 8.0 * 8.0; // moving this far = progress
    private static final int STATION_GOAL_RANGE = 3; // arrival radius for look-out stations
    private static final Block[] PILLAR_MATERIALS = {
        net.minecraft.world.level.block.Blocks.GRASS_BLOCK, // everywhere underfoot, drops dirt
        net.minecraft.world.level.block.Blocks.DIRT,
        net.minecraft.world.level.block.Blocks.STONE,
        net.minecraft.world.level.block.Blocks.COBBLESTONE,
        net.minecraft.world.level.block.Blocks.GRAVEL,
        net.minecraft.world.level.block.Blocks.SAND
    };

    private Block targetBlock;
    /** The original requested resource (never overwritten by pillar material runs). */
    private Block resourceBlock;
    private ResourceBlocks.ResourceYield resourceYield;
    private Set<Block> miningBlocks;
    private int targetQuantity;
    private int gatheredCount;
    private boolean fillMode;
    private boolean anyLogMode;
    private boolean logTarget;
    /** Resource count in the inventory at action start - quota is a delta over this. */
    private int startResourceCount;
    private int lastProgressCount;
    private long lastProgressTick;
    /** Position of the last progress event - moving away from it also resets the timeout. */
    private BlockPos lastProgressPos;
    private int expandDir;
    private BlockPos origin;
    private ResourceSearchPlanner.SearchState searchState;
    private BlockPos routeTarget;
    private BlockPos mineTarget;
    private int ticksOnMine;
    private String memoryKey;
    /**
     * Monitor-driven routing state (Task 4 machinery): the goal is recreated per
     * route target, and both are reset whenever the route target changes - the
     * replacement for the old per-target {@code routeStallCount} reset.
     */
    private VasyanGoal routeGoal;
    private PathMonitor routeMonitor;
    private PathBudgets routeBudgets;
    private BlockPos lastRouteTarget;
    /** Bot cell when the current route attempt began (to detect planning-only bail). */
    private BlockPos routeStartPos;
    private int ticksRunning;
    private int statusCooldown;

    /** Visible-but-unreachable targets: skip them instead of looping forever. */
    private final Set<BlockPos> unreachableTargets = new HashSet<>();
    /** Centers of failed vertical pockets; nearby targets in the same pit are skipped too. */
    private final List<BlockPos> verticalTrapCenters = new ArrayList<>();
    /** Adjacent blocks of the same resource discovered by mining a vein. */
    private final Set<BlockPos> veinTargets = new LinkedHashSet<>();

    // Fell mode state
    private boolean fellMode;
    private boolean fellGatheringMaterial;
    private Block fellLogBlock;
    private final List<BlockPos> fellLogs = new ArrayList<>();
    /** Every pillar block WE placed - dismantle exactly these, never guess by
     * block type (a pillar built from same-type logs is otherwise mistaken
     * for the tree itself and left standing). */
    private final List<BlockPos> fellPillar = new ArrayList<>();
    private int fellHeight;
    private int fellStallTicks;
    private int fellWaitTicks;

    private enum Phase { SEARCH, ROUTING, MINING, FELL_ASCEND, FELL_DESCEND, FELL_GATHER, FELL_WAIT, FELL_CLEANUP, FINISHED }

    private Phase phase = Phase.SEARCH;

    public GatherResourceAction(VasyanEntity vasyan, Task task) {
        super(vasyan, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("resource");
        if (blockName == null || blockName.isBlank()) {
            blockName = task.getStringParameter("block");
        }
        targetQuantity = task.getIntParameter("quantity", 16);
        fillMode = "true".equalsIgnoreCase(String.valueOf(task.getParameters().getOrDefault("fill", "false")));

        // "Gather wood/tree" means ANY log (oak, birch, spruce...) - the LLM
        // may name a single type, but the user asked for wood in general.
        anyLogMode = ResourceBlocks.isWoodRequest(blockName);
        resourceYield = anyLogMode ? null : ResourceBlocks.yieldFor(blockName);
        if (!anyLogMode && resourceYield == null) {
            result = ActionResult.failure("Unknown resource: " + blockName);
            return;
        }

        targetBlock = anyLogMode ? null : ResourceBlocks.parseBlock(blockName);
        resourceBlock = targetBlock;
        miningBlocks = anyLogMode ? null : resourceYield.miningBlocks();
        logTarget = anyLogMode || (targetBlock != null
            && targetBlock.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS));
        memoryKey = anyLogMode ? "wood" : blockName;
        long now = vasyan.level().getGameTime();
        GlobalResourceMemory.prune(now, VasyanConfig.GATHER_MEMORY_TTL_TICKS.get());

        gatheredCount = 0;
        // Quota counts what actually reaches the inventory (pickup fact),
        // as a delta over what was already there ("mine 50 MORE logs").
        startResourceCount = countResource();
        ticksRunning = 0;
        ticksOnMine = 0;
        lastRouteTarget = null;
        routeGoal = null;
        routeMonitor = null;
        routeBudgets = null;
        fellMode = false;
        fellGatheringMaterial = false;
        fellHeight = 0;
        fellStallTicks = 0;
        fellLogs.clear();
        fellPillar.clear();
        unreachableTargets.clear();
        verticalTrapCenters.clear();
        veinTargets.clear();
        origin = vasyan.blockPosition();
        searchState = new ResourceSearchPlanner.SearchState(origin, 0, 0, vasyan.level().getGameTime());

        // Ground movement only - never fly while gathering
        vasyan.setFlying(false);
        vasyan.getNavigation().stop();
        lastProgressTick = vasyan.level().getGameTime();
        lastProgressPos = vasyan.blockPosition();

        debugLog("GATHER", "search " + resourceLabel() + " x" + targetQuantity
            + " from " + origin);
    }

    @Override
    protected void onTick() {
        if (phase == Phase.FINISHED) {
            return;
        }
        ticksRunning++;

        // The quota counts what actually reached the INVENTORY (pickup
        // fact), not what was broken: drops lost in water/lava and logs
        // currently spent on a pillar must not inflate the count.
        gatheredCount = Math.max(0, countResource() - startResourceCount);

        // Search timeout only counts from the last PROGRESS: a bot that
        // keeps mining trees must never be killed by "Search timed out" -
        // the clock resets on every gathered log. Long walks/swims between
        // trees and stations (40-50s across a swamp, no chop) are progress
        // too: only a truly idle bot times out.
        long now = vasyan.level().getGameTime();
        if (gatheredCount != lastProgressCount) {
            lastProgressCount = gatheredCount;
            lastProgressTick = now;
            lastProgressPos = vasyan.blockPosition();
        } else if (lastProgressPos != null
                && vasyan.blockPosition().distSqr(lastProgressPos) >= PROGRESS_MOVE_DISTANCE_SQ) {
            lastProgressTick = now;
            lastProgressPos = vasyan.blockPosition();
        }
        if (now - lastProgressTick >= VasyanConfig.GATHER_SEARCH_TIMEOUT.get()) {
            finish(false, "Search timed out - found " + gatheredCount + " " + resourceLabel());
            return;
        }

        if (fillMode) {
            // Fill mode: keep mining while there is any room left for the
            // requested resource (empty slot or a partially filled stack).
            // In any-log mode any free slot counts (mixed log types).
            boolean hasRoom = anyLogMode
                ? vasyan.getInventory().hasFreeSpace()
                : vasyan.getInventory().hasSpaceFor(currentTargetItem());
            if (!hasRoom) {
                finish(true, "Inventory full - gathered " + gatheredCount + " " + resourceLabel());
                return;
            }
        } else if (!vasyan.getInventory().hasFreeSpace()) {
            finish(true, "Inventory full");
            return;
        }

        // Completion is measured by blocks actually mined this session
        // (gatheredCount), NOT by the whole inventory: if the bot already had
        // 30 oak logs and the player asks for 50, exactly 50 more are mined
        // (80 total) - comparing the inventory would stop at 20 (bug).
        if (gatheredCount >= targetQuantity) {
            finish(true, "Gathered " + gatheredCount + " " + resourceLabel());
            return;
        }

        switch (phase) {
            case SEARCH -> phaseSearch();
            case ROUTING -> phaseRouting();
            case MINING -> phaseMining();
            case FELL_ASCEND -> phaseFellAscend();
            case FELL_DESCEND -> phaseFellDescend();
            case FELL_GATHER -> phaseFellGatherMaterial();
            case FELL_WAIT -> phaseFellWaitPickup();
            case FELL_CLEANUP -> phaseFellCleanup();
            default -> { }
        }

        // Periodic STATUS ping so /vasyan debug shows what the bot is doing
        // even in silently-looped phases (stuck in water, circling a tree).
        if (--statusCooldown <= 0) {
            statusCooldown = STATUS_INTERVAL;
            BlockPos p = routeTarget;
            debugLog("STATUS",
                "phase=" + phase
                + " pos=" + vasyan.blockPosition()
                + " route=" + (p != null ? p : "-")
                + " dist=" + (p != null ? Math.round(Math.sqrt(horizontalDistanceSqr(p))) + "b" : "-")
                + " " + (vasyan.isInWater() ? "WATER " : "")
                + " nav=" + (vasyan.getNavigation().isInProgress() ? "moving" : "stopped")
                + " " + gatheredCount + "/" + targetQuantity);
        }
    }

    /** The item we are actually counting: logs while felling, else the target yield item. */
    private Item currentTargetItem() {
        if (fellMode && fellLogBlock != null) {
            return fellLogBlock.asItem();
        }
        if (anyLogMode) {
            return Items.OAK_LOG;
        }
        if (resourceYield != null && resourceYield.representativeItem() != null) {
            return resourceYield.representativeItem();
        }
        return targetBlock == null ? Items.AIR : targetBlock.asItem();
    }

    /** Human-readable resource name ("Oak Log" or "Wood" in any-log mode). */
    private String resourceLabel() {
        if (anyLogMode) {
            return "Wood";
        }
        return targetBlock != null ? targetBlock.getName().getString() : "?";
    }

    // ---- phases ----

    private void phaseSearch() {
        if (fellGatheringMaterial) {
            // A material run gone sideways must never look for the resource
            // itself (targetBlock is temporarily a material like dirt).
            phase = Phase.FELL_GATHER;
            return;
        }
        // Nearest material wins. Merge ray-visible blocks with the
        // no-line-of-sight nearby scan and pick the PHYSICALLY closest
        // candidate - a tree hidden behind a canopy 3 blocks away must win
        // over a visible one 30 blocks away (the bot used to skip nearby
        // trees and walk off into the distance).
        List<BlockPos> visible = anyLogMode
            ? VisionScanner.findVisibleAnyLog(vasyan)
            : VisionScanner.findVisible(vasyan, miningBlocks);
        boolean logTarget = anyLogMode
            || (targetBlock != null && targetBlock.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS));

        // Logs: brute-force no-LOS scan (canopies hide trunks). Ores: the same
        // nearby cube scan, but filtered to EXPOSED blocks with a standable
        // approach cell - anti-xray stays intact (buried ore is never
        // returned), while an exposed coal face just around a terrain lip is
        // found even though the eye ray from the bot clips the ground.
        List<BlockPos> nearby = allowsNoLosNearbyScan(logTarget)
            ? VisionScanner.findNearbyBlocks(vasyan, NEARBY_SCAN_RADIUS, miningBlocks)
            : VisionScanner.findNearbyExposedBlocks(vasyan, NEARBY_SCAN_RADIUS, miningBlocks);
        if (logTarget) {
            // lone logs of player buildings are not trees
            nearby = nearby.stream().filter(this::isTreeLog).toList();
        }

        List<BlockPos> all = new java.util.ArrayList<>(visible.size() + nearby.size());
        all.addAll(visible);
        all.addAll(nearby);
        long nowMem = vasyan.level().getGameTime();
        int memoryRadius = VasyanConfig.GATHER_MEMORY_RADIUS.get();
        BlockPos center = vasyan.blockPosition();
        BlockPos mine = all.stream()
            .filter(p -> !unreachableTargets.contains(p))
            .filter(p -> !isInVerticalTrap(p))
            .filter(p -> !GlobalResourceMemory.isUnreachable(memoryKey, p, nowMem, memoryRadius))
            .filter(p -> !isUnderwaterTarget(p)) // swamp: never dive for a log (drop loss, air)
            .min(Comparator.comparingDouble(p -> vasyan.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
            .orElse(null);
        if (mine != null) {
            if (!visible.contains(mine)) {
                debugLog("SEARCH", "nearby target at " + mine
                    + (logTarget ? " (behind foliage)" : " (exposed face around the corner)"));
            }
            mineTarget = mine;
            routeTarget = mine;
            phase = Phase.ROUTING;
            return;
        }

        // If we just arrived at a look-out station and it has no targets, mark it empty
        // before picking the next one. Do NOT mark a mine target or a station we have
        // not reached yet.
        if (routeTarget != null && routeGoal != null
                && (mineTarget == null || !routeTarget.equals(mineTarget))
                && routeGoal.hasReached(vasyan.blockPosition())) {
            GlobalResourceMemory.rememberEmptyStation(memoryKey, routeTarget, nowMem);
        }

        // A target existed but all were unreachable by land (swamp islands):
        // blacklist them so we do not re-pick and re-walk into the water.
        for (BlockPos p : all) {
            if (!unreachableTargets.contains(p) && isUnderwaterTarget(p)) {
                unreachableTargets.add(p);
            }
        }
        unreachableTargets.retainAll(all); // keep the set small

        // Vein following takes priority over a fresh world scan: after mining a block
        // the bot looks at exposed neighbors and keeps digging the same vein instead
        // of walking away.
        if (!fellMode) {
            veinTargets.removeIf(p -> !isLogBlockAt(p) || unreachableTargets.contains(p)
                || isInVerticalTrap(p));
            BlockPos nextVein = veinTargets.stream()
                .min(Comparator.comparingDouble(p -> vasyan.distanceToSqr(
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(null);
            if (nextVein != null) {
                debugLog("SEARCH", "continue vein at " + nextVein);
                mineTarget = nextVein;
                routeTarget = nextVein;
                phase = Phase.ROUTING;
                return;
            }
        }

        // No target anywhere: advance the route
        if (!ResourceSearchPlanner.hasNext(searchState, VasyanConfig.GATHER_SEARCH_RADIUS.get(),
                VasyanConfig.GATHER_RING_SPACING.get())) {
            // Origin rings exhausted: keep searching outward - walk away from
            // spawn in a compass sweep, widening each full turn, instead of
            // giving up ("Nothing found") or standing still forever.
            expandDir++;
            BlockPos station = expandStation();
            debugLog("SEARCH", "no targets locally, expanding outward to " + station);
            routeTarget = station;
            phase = Phase.ROUTING;
            return;
        }

        BlockPos station = ResourceSearchPlanner.stationFor(searchState,
            VasyanConfig.GATHER_RING_SPACING.get(), VasyanConfig.GATHER_STATIONS_PER_RING.get());
        while (ResourceSearchPlanner.hasNext(searchState, VasyanConfig.GATHER_SEARCH_RADIUS.get(),
                VasyanConfig.GATHER_RING_SPACING.get())
            && GlobalResourceMemory.isEmptyStation(memoryKey, station, nowMem, memoryRadius)) {
            searchState = ResourceSearchPlanner.next(searchState, VasyanConfig.GATHER_STATIONS_PER_RING.get());
            station = ResourceSearchPlanner.stationFor(searchState,
                VasyanConfig.GATHER_RING_SPACING.get(), VasyanConfig.GATHER_STATIONS_PER_RING.get());
        }
        searchState = ResourceSearchPlanner.next(searchState, VasyanConfig.GATHER_STATIONS_PER_RING.get());
        debugLog("SEARCH", "no target visible, next station " + station);

        routeTarget = station;
        phase = Phase.ROUTING;
    }

    private void phaseRouting() {
        vasyan.setFlying(false); // ground movement, always

        if (routeTarget == null) {
            phase = Phase.SEARCH;
            return;
        }
        // New route target: fresh goal, monitor and budgets for this attempt -
        // the replacement of the old per-target stall-state reset. A mine
        // target uses near() 3D-Chebyshev so ore/wood above or below the bot's
        // feet are reachable (strict side-adjacency would make those unreachable);
        // a look-out station is reached within a small radius.
        if (!routeTarget.equals(lastRouteTarget)) {
            lastRouteTarget = routeTarget;
            routeStartPos = vasyan.blockPosition();
            routeGoal = routeTarget.equals(mineTarget)
                ? VasyanGoal.near(routeTarget, 1)
                : VasyanGoal.near(routeTarget, STATION_GOAL_RANGE);
            routeBudgets = PathBudgets.start(System.nanoTime(),
                VasyanConfig.NAV_THINK_TIMEOUT_MS.get(),
                VasyanConfig.NAV_TICK_TIMEOUT_MS.get(),
                VasyanConfig.NAV_SEARCH_RADIUS.get());
            routeMonitor = VasyanPathing.moveTo(vasyan, routeGoal, routeBudgets);
        }

        long nowNano = System.nanoTime();
        BlockPos botPos = vasyan.blockPosition();

        // think-budget bounds PLANNING only: if the route attempt ran out of
        // time before the bot ever moved, blacklist the target as before. Once
        // the bot is moving the monitor's own budgets (stall windows + paced
        // replans + recovery ladder) govern - a long walk is not a planning
        // failure (review #39).
        boolean moved = routeStartPos != null && !botPos.equals(routeStartPos);
        if (routeBudgets.thinkExpired(nowNano) && !moved && !routeMonitor.inLadderRecovery()) {
            debugLog("ROUTING", "think budget exhausted before movement for " + routeGoal.describe());
            skipCurrentRouteTarget();
            return;
        }

        // A visible resource can be mined as soon as it is inside the real
        // mining reach. Requiring near(target, 1) first deadlocks on blocks
        // directly above/below the bot: horizontal navigation reaches dist=0,
        // but 3D Chebyshev still does not (e.g. same X/Z, dy=2), so ROUTING
        // falls into the recovery ladder while the target is already mineable.
        if (routeTarget.equals(mineTarget) && canMineFromHere(mineTarget)) {
            vasyan.getNavigation().stop();
            phase = Phase.MINING;
            return;
        }

        // Station arrival is the goal predicate; mine-target arrival above is
        // reach-based. Water needs no special case: amphibious navigation
        // swims across ponds on its own.
        if (routeGoal.hasReached(botPos)) {
            if (routeTarget.equals(mineTarget)) {
                phase = Phase.MINING; // we arrived at the resource block
            } else {
                phase = Phase.SEARCH; // arrived at station: scan again
            }
            return;
        }

        // Monitor gave up after exhausting its recovery ladder (replans,
        // dig-through, scaffold, hop-teleport): skip this target exactly as
        // the old repeated-stall path did.
        if (routeMonitor.finished()) {
            debugLog("ROUTING", "path monitor gave up on " + routeGoal.describe());
            skipCurrentRouteTarget();
            return;
        }

        routeBudgets = routeBudgets.nextTick(nowNano);
        boolean allowRecovery = !(routeTarget.equals(mineTarget) && !logTarget);
        VasyanPathing.enforce(vasyan, routeMonitor, allowRecovery);
    }

    /**
     * Routing failure exit for the CURRENT target: blacklists an unreachable
     * resource block (so the next scan does not pick the SAME block again)
     * or just moves on to the next station, keeping every fell-mode handoff
     * intact.
     */
    private void skipCurrentRouteTarget() {
        vasyan.getNavigation().stop();
        if (mineTarget != null && routeTarget.equals(mineTarget)) {
            rememberUnreachable(mineTarget);
            GlobalResourceMemory.rememberUnreachable(memoryKey, mineTarget, vasyan.level().getGameTime());
            rememberVerticalTrap(mineTarget);
            debugLog("ROUTING", "target unreachable, skipping " + mineTarget);
            mineTarget = null;
            if (fellGatheringMaterial) {
                phase = Phase.FELL_GATHER; // re-pick another material block
                return;
            }
            if (fellMode) {
                // Drop only this branch from the CURRENT tree; the rest is
                // still chopped via the cleanup loop, not abandoned.
                fellLogs.remove(routeTarget);
                continueFellCleanup();
                return;
            }
        } else {
            debugLog("ROUTING", "station unreachable, next station");
            GlobalResourceMemory.rememberEmptyStation(memoryKey, routeTarget, vasyan.level().getGameTime());
        }
        veinTargets.clear();
        phase = Phase.SEARCH; // next station / other candidate
    }

    private void phaseMining() {
        if (mineTarget == null) {
            phase = Phase.SEARCH;
            return;
        }

        // Target block gone (already mined by someone else / dropped).
        // NOTE: in any-log mode targetBlock is null, so compare via
        // isLogBlockAt (LOGS tag) - block != null would always be true.
        if (!isLogBlockAt(mineTarget)) {
            if (fellMode && !fellGatheringMaterial) {
                // A cleanup branch of the current tree vanished (broken
                // externally between scans): drop it and keep chopping.
                fellLogs.remove(mineTarget);
                mineTarget = null;
                ticksOnMine = 0;
                continueFellCleanup();
                return;
            }
            mineTarget = null;
            ticksOnMine = 0;
            phase = Phase.SEARCH;
            return;
        }

        // Not close enough: walk to it
        if (!canMineFromHere(mineTarget)) {
            if (!vasyan.getNavigation().isInProgress()) {
                vasyan.getNavigation().moveTo(mineTarget.getX() + 0.5, mineTarget.getY(), mineTarget.getZ() + 0.5, 1.0);
            }
            // Visible but unreachable ore (cliff, lava): give up after a grace
            // period and remember the spot, instead of re-pathfinding forever.
            ticksOnMine++;
            if (ticksOnMine > MINE_STALL_TICKS && vasyan.getNavigation().isDone()) {
                ticksOnMine = 0;
                vasyan.getNavigation().stop();
                rememberUnreachable(mineTarget);
                GlobalResourceMemory.rememberUnreachable(memoryKey, mineTarget, vasyan.level().getGameTime());
                rememberVerticalTrap(mineTarget);
                if (fellMode && !fellGatheringMaterial) {
                    // Unreachable cleanup branch: drop it from the CURRENT
                    // tree's list only, keep chopping the rest.
                    fellLogs.remove(mineTarget);
                    mineTarget = null;
                    continueFellCleanup();
                    return;
                }
                mineTarget = null;
                phase = Phase.SEARCH;
            }
            return;
        }

        // In reach: break ONLY this block (no tunneling)
        vasyan.swing(InteractionHand.MAIN_HAND, true);
        if (!vasyan.level().destroyBlock(mineTarget, true)) {
            return; // failed to break - retry next tick
        }
        ticksOnMine = 0;

        if (fellGatheringMaterial) {
            // Gather enough pillar material: switch back to logs only once a
            // usable block is actually in the inventory. The drop needs a few
            // ticks to be vacuumed - wait instead of digging forever.
            targetBlock = fellLogBlock;
            fellGatheringMaterial = false;
            boolean havePillarBlock = !FellSupport.findSolidPillarBlock(vasyan.level(),
                vasyan.blockPosition(), vasyan.getInventory(), fellLogBlock).isEmpty();
            phase = havePillarBlock ? Phase.FELL_ASCEND : Phase.FELL_WAIT;
            return;
        }

        // No gatheredCount++ here: the quota counts the PICKUP fact
        // (inventory delta, updated in onTick), not the break fact.
        BlockPos mined = mineTarget;
        debugLog("MINE", resourceLabel() + " at " + mined
            + " (" + gatheredCount + "/" + targetQuantity + ")");

        if (!fellMode) {
            veinTargets.addAll(collectVeinTargets(mined));
        }

        // Enter whole-tree felling: a log above the mined one means a tree
        // trunk - but only when leaves are nearby (player structures must
        // never be felled, even if built from logs)
        BlockPos above = mined.above();
        mineTarget = null;
        if (!fellMode && isLogBlockAt(above)
                && isTreeLog(above)) {
            // NOTE: compare against targetBlock here - isTargetLog() uses
            // fellLogBlock which is only set inside enterFellMode().
            List<BlockPos> component = FellSupport.collectConnectedLogs(above,
                this::isLogBlockAt, FELL_MAX_LOGS);
            if (component.size() >= 2) { // trunk (or trunk+branches) = a tree, not a lone log
                enterFellMode(component);
                return;
            }
        }
        if (fellMode) {
            // Chopped one of the current tree's cleanup branches: drop it
            // from the list and continue with the next one instead of
            // slipping into SEARCH and abandoning the half-felled tree.
            fellLogs.remove(mined);
            continueFellCleanup();
            return;
        }
        phase = Phase.SEARCH; // look for the next visible block
    }

    /** Whether the bot can break {@code target} from its current position. */
    private boolean canMineFromHere(BlockPos target) {
        return canMineFrom(
            vasyan.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5),
            VisionScanner.hasLineOfSight(vasyan, target));
    }

    /** Raw no-LOS discovery is a foliage workaround for logs only; ores use the
     *  exposed-face nearby scan ({@link VisionScanner#findNearbyExposedBlocks})
     *  instead, so buried ore stays invisible (anti-xray). */
    static boolean allowsNoLosNearbyScan(boolean logTarget) {
        return logTarget;
    }

    /** Reach alone is not enough: breaking a hidden block through terrain is x-ray mining. */
    static boolean canMineFrom(double distanceSq, boolean lineOfSight) {
        return lineOfSight && distanceSq <= MINE_REACH_SQ;
    }

    /** A log counts as a tree log when leaves are within 5 blocks. */
    private boolean isTreeLog(BlockPos pos) {
        return FellSupport.hasNearbyBlock(pos,
            p -> vasyan.level().getBlockState(p).getBlock() instanceof LeavesBlock, 5);
    }

    // ---- fell mode ----

    private void enterFellMode(List<BlockPos> component) {
        // Keep only the logs ABOVE the water line: underwater trunk logs in
        // swamps would make the bot walk into the pond to chop them.
        List<BlockPos> aboveWater = component.stream()
            .filter(p -> !isUnderwaterTarget(p))
            .toList();
        if (aboveWater.isEmpty()) {
            return; // whole tree underwater - nothing to fell, stay in MINING
        }
        // The flag goes up only AFTER the validation above: an early return
        // with fellMode already true left the mode stuck on forever (phase
        // stayed MINING with a null target, and the !fellMode entry check in
        // phaseMining never fired again - every later tree was ground-chopped).
        fellMode = true;
        // Concrete log type: the exact target, or the type of the first
        // connected log when in any-log (wood) mode.
        fellLogBlock = targetBlock != null
            ? targetBlock
            : vasyan.level().getBlockState(aboveWater.get(0)).getBlock();
        fellHeight = 0;
        fellStallTicks = 0;
        fellLogs.clear();
        fellLogs.addAll(aboveWater);
        debugLog("FELL", "whole-tree felling: " + aboveWater.size() + " logs (underwater: "
            + (component.size() - aboveWater.size()) + " skipped)");
        phase = Phase.FELL_ASCEND;
    }

    /**
     * Total count of matching items currently in the inventory. Static for
     * unit tests (tag bindings are unavailable without a running server, so
     * the matcher is injected).
     */
    static int countResource(net.minecraft.world.Container inventory,
            java.util.function.Predicate<net.minecraft.world.item.Item> resourceMatcher) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && resourceMatcher.test(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countResource() {
        java.util.function.Predicate<net.minecraft.world.item.Item> matcher;
        if (anyLogMode) {
            // In-game only: item tag bindings require a running server.
            matcher = item -> item.builtInRegistryHolder().is(net.minecraft.tags.ItemTags.LOGS);
        } else if (resourceYield != null) {
            matcher = resourceYield.itemMatcher();
        } else {
            matcher = item -> false;
        }
        return countResource(vasyan.getInventory(), matcher);
    }

    /**
     * Outward search station: compass sweep around the origin, widening by
     * 16 blocks per full turn. Used once the near rings are exhausted so the
     * bot keeps moving to find resources instead of giving up.
     */
    private BlockPos expandStation() {
        double angle = (expandDir % 8) * Math.PI / 4;
        int ring = expandDir / 8;
        int radius = VasyanConfig.GATHER_SEARCH_RADIUS.get() + 16 * (ring + 1);
        int x = origin.getX() + (int) Math.round(radius * Math.cos(angle));
        int z = origin.getZ() + (int) Math.round(radius * Math.sin(angle));
        int y = vasyan.level().getHeightmapPos(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            new BlockPos(x, origin.getY(), z)).getY();
        return new BlockPos(x, y, z);
    }

    /**
     * Whether the block at pos is the current mining target: ANY log in
     * any-log mode, the specific set of mining blocks for a yield-based
     * resource, or the temporary pillar material while gathering it.
     */
    private boolean isLogBlockAt(BlockPos pos) {
        Block block = vasyan.level().getBlockState(pos).getBlock();
        if (fellGatheringMaterial) {
            // targetBlock is temporarily dirt/grass/etc.; check the material.
            return block == targetBlock;
        }
        if (anyLogMode) {
            return block.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS);
        }
        return miningBlocks != null && miningBlocks.contains(block);
    }

    /**
     * A log we would have to stand IN water to mine: either the block itself
     * is waterlogged, or the ground below it is water. Swamp trees drop their
     * lowest logs into the pond - skipping them keeps the bot dry.
     */
    private boolean isUnderwaterTarget(BlockPos pos) {
        net.minecraft.world.level.Level lvl = vasyan.level();
        return lvl.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)
            || lvl.getFluidState(pos.below()).is(net.minecraft.tags.FluidTags.WATER);
    }

    private void exitFellMode() {
        fellMode = false;
        fellGatheringMaterial = false;
        targetBlock = fellLogBlock;
        fellHeight = 0;
        fellStallTicks = 0;
        fellLogs.clear();
        fellPillar.clear();
    }

    private boolean isTargetLog(BlockPos pos) {
        return vasyan.level().getBlockState(pos).getBlock() == fellLogBlock;
    }

    /** Collects adjacent blocks of the current target around a freshly mined block. */
    private List<BlockPos> collectVeinTargets(BlockPos mined) {
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos p = mined.offset(dx, dy, dz);
                    if (isLogBlockAt(p) && VisionScanner.isExposedForMining(vasyan.level(), p,
                            vasyan.level().getBlockState(p).getBlock())) {
                        found.add(p);
                    }
                }
            }
        }
        return found;
    }

    private void phaseFellAscend() {
        vasyan.setFlying(false);
        // Climbing is manual (setPos): an active navigation would drag the
        // Vasyan off the pillar back to the ground - stop it before ascending.
        if (vasyan.getNavigation().isInProgress()) {
            vasyan.getNavigation().stop();
        }

        // Stall guard: progress is a felled log OR a grown pillar
        fellStallTicks++;
        if (fellStallTicks > FELL_STALL_TICKS) {
            // A fell stall abandons only the CURRENT tree - the gather run
            // continues (finish() here used to kill the whole action).
            abandonTree("Stuck while felling (no progress for " + FELL_STALL_TICKS + " ticks)");
            return;
        }

        // 1. Fell any remaining log of the component within reach (branches!)
        BlockPos reachable = null;
        for (BlockPos log : fellLogs) {
            if (vasyan.distanceToSqr(log.getX() + 0.5, log.getY() + 0.5, log.getZ() + 0.5) <= MINE_REACH_SQ) {
                reachable = log;
                break;
            }
        }
        if (reachable != null) {
            vasyan.swing(InteractionHand.MAIN_HAND, true);
            if (vasyan.level().destroyBlock(reachable, true)) {
                // No gatheredCount++ here either: the quota is the pickup
                // delta, recomputed every tick in onTick
                fellLogs.remove(reachable);
                fellStallTicks = 0;
                debugLog("FELL", "felled " + fellLogBlock.getName().getString() + " at " + reachable
                    + " (" + gatheredCount + "/" + targetQuantity + ")");
            } else {
                // The log vanished (already broken elsewhere): stop retrying it
                fellLogs.remove(reachable);
                debugLog("FELL", "log at " + reachable + " already gone, skipping");
            }
            return;
        }

        // 2. Logs still above us? Climb the pillar (real block from inventory)
        int vasyanY = vasyan.blockPosition().getY();
        boolean logAbove = fellLogs.stream().anyMatch(p -> p.getY() > vasyanY);
        if (logAbove && fellHeight < FELL_MAX_HEIGHT) {
            BlockPos standPos = vasyan.blockPosition();
            ItemStack pillarBlock = FellSupport.findSolidPillarBlock(vasyan.level(), standPos,
                vasyan.getInventory(), fellLogBlock);
            if (pillarBlock.isEmpty()) {
                debugLog("FELL", "no pillar block in inventory - gathering material");
                phase = Phase.FELL_GATHER; // gather dirt/stone first
                return;
            }
            Block block = ((BlockItem) pillarBlock.getItem()).getBlock();
            BlockState standState = vasyan.level().getBlockState(standPos);
            // Leaves (and any other block in the way) are cleared first - the
            // canopy must not block the pillar. Drops are vacuumed.
            if (!standState.canBeReplaced()) {
                vasyan.swing(InteractionHand.MAIN_HAND, true);
                vasyan.level().destroyBlock(standPos, true);
                debugLog("FELL", "cleared " + standState.getBlock().getName().getString() + " at " + standPos);
                return; // retry next tick once the way is clear
            }
            vasyan.level().setBlock(standPos, block.defaultBlockState(), 3);
            vasyan.setPos(standPos.getX() + 0.5, standPos.getY() + 1, standPos.getZ() + 0.5);
            fellPillar.add(standPos);
            // Remove one block from the inventory slot that held it
            for (int i = 0; i < vasyan.getInventory().getContainerSize(); i++) {
                ItemStack slot = vasyan.getInventory().getItem(i);
                if (!slot.isEmpty() && slot.getItem() == pillarBlock.getItem()) {
                    vasyan.getInventory().removeItem(i, 1);
                    break;
                }
            }
            fellHeight++;
            fellStallTicks = 0;
            debugLog("FELL", "pillar up to y=" + (standPos.getY() + 1) + " (height " + fellHeight + ")");
            return;
        }

        // 3. No logs above (or height limit): dismantle the pillar on the way down
        phase = Phase.FELL_DESCEND;
    }

    private void phaseFellDescend() {
        if (vasyan.getNavigation().isInProgress()) {
            vasyan.getNavigation().stop();
        }
        fellStallTicks++;
        if (fellStallTicks > FELL_STALL_TICKS) {
            abandonTree("Stuck while dismantling the pillar");
            return;
        }

        BlockPos below = vasyan.blockPosition().below();
        BlockState belowState = vasyan.level().getBlockState(below);

        if (fellHeight > 0) {
            if (fellPillar.contains(below)) {
                // Our own pillar block - even a same-type log (the fallback
                // pillar material IS the target block): dismantle it, the
                // drop returns to the inventory via vacuum
                vasyan.swing(InteractionHand.MAIN_HAND, true);
                if (vasyan.level().destroyBlock(below, true)) {
                    fellPillar.remove(below);
                    vasyan.setPos(below.getX() + 0.5, below.getY(), below.getZ() + 0.5);
                    fellHeight--;
                    fellStallTicks = 0;
                }
                return;
            }
            if (belowState.isAir()) {
                // Pillar block was destroyed externally: just fall down a level
                vasyan.setPos(below.getX() + 0.5, below.getY(), below.getZ() + 0.5);
                fellHeight--;
                fellStallTicks = 0;
                return;
            }
            // Solid block below that is not our pillar (e.g. the tree's own
            // log we stand on after a branch fell): drop straight down onto it
            vasyan.setPos(below.getX() + 0.5, below.getY(), below.getZ() + 0.5);
            fellHeight--;
            fellStallTicks = 0;
            return;
        }

        // Back on the ground. Leftover branch logs (too far out to chop from
        // the pillar) go through the cleanup phase, which sets a REAL mine
        // target - the old hand-off routed to them with mineTarget==null, so
        // every routing exit slipped into SEARCH and the tree was abandoned.
        phase = Phase.FELL_CLEANUP;
    }

    /**
     * Leftover branch logs after the descent: walk to the nearest one and
     * chop it like a normal mining target. BOTH the route and the mine
     * target are set, so arrival, mine-from-here and stall handling treat it
     * as a resource block (with mineTarget null every exit slipped into
     * SEARCH and the tree was abandoned half-felled). Loops via
     * ROUTING/MINING until fellLogs is empty, then exits fell mode.
     */
    private void phaseFellCleanup() {
        if (fellLogs.isEmpty()) {
            debugLog("FELL", "tree felled, pillar dismantled");
            exitFellMode();
            phase = Phase.SEARCH;
            return;
        }
        BlockPos nearest = fellLogs.stream()
            .min(Comparator.comparingDouble(p -> horizontalDistanceSqr(p)))
            .orElse(null);
        mineTarget = nearest;
        routeTarget = nearest;
        debugLog("FELL", "cleanup: " + fellLogs.size() + " branch logs left, walking to " + nearest);
        phase = Phase.ROUTING;
    }

    /**
     * After a cleanup branch was chopped, dropped as unreachable, or found
     * already gone: continue with the next branch, or (none left) exit fell
     * mode and resume the normal search.
     */
    private void continueFellCleanup() {
        if (!fellLogs.isEmpty()) {
            phase = Phase.FELL_CLEANUP;
            return;
        }
        debugLog("FELL", "tree felled");
        exitFellMode();
        phase = Phase.SEARCH;
    }

    private void phaseFellGatherMaterial() {
        // Find a solid material to build the pillar from - but never
        // the block right under our feet (digging it would leave a hole)
        BlockPos feet = vasyan.blockPosition().below();
        for (Block material : PILLAR_MATERIALS) {
            List<BlockPos> visible = VisionScanner.findVisible(vasyan, material);
            BlockPos chosen = visible.stream()
                .filter(p -> !p.equals(feet) && !p.equals(vasyan.blockPosition()))
                .filter(p -> !unreachableTargets.contains(p))
                .min(Comparator.comparingDouble(p -> vasyan.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(null);
            if (chosen != null) {
                mineTarget = chosen;
                routeTarget = chosen;
                targetBlock = material;
                fellGatheringMaterial = true;
                phase = Phase.ROUTING;
                return;
            }
        }

        // Nothing visible via ray scan (forest canopy, swamp reeds, water):
        // brute-force cube scan - grass/dirt is almost always right next to
        // the bot, it just cannot "see" it through the leaves.
        for (Block material : PILLAR_MATERIALS) {
            List<BlockPos> nearby = VisionScanner.findNearbyBlocks(vasyan, NEARBY_SCAN_RADIUS, material);
            BlockPos chosen = nearby.stream()
                .filter(p -> !p.equals(feet) && !p.equals(vasyan.blockPosition()))
                .filter(p -> !unreachableTargets.contains(p))
                .min(Comparator.comparingDouble(p -> vasyan.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(null);
            if (chosen != null) {
                debugLog("FELL", "material found by nearby scan: " + material.getName().getString() + " at " + chosen);
                mineTarget = chosen;
                routeTarget = chosen;
                targetBlock = material;
                fellGatheringMaterial = true;
                phase = Phase.ROUTING;
                return;
            }
        }
        finish(false, "No blocks left to climb the tree with");
    }

    /**
     * After mining a material block the drop needs a few ticks to be vacuumed
     * into the inventory. Wait briefly instead of mining another block (which
     * previously looped forever when the drop never arrived).
     */
    private void phaseFellWaitPickup() {
        fellWaitTicks++;
        boolean havePillarBlock = !FellSupport.findSolidPillarBlock(vasyan.level(),
            vasyan.blockPosition(), vasyan.getInventory(), fellLogBlock).isEmpty();
        if (havePillarBlock) {
            fellWaitTicks = 0;
            phase = Phase.FELL_ASCEND;
            return;
        }
        if (fellWaitTicks > FELL_WAIT_TICKS) {
            fellWaitTicks = 0;
            phase = Phase.FELL_GATHER; // try mining another material block
        }
    }

    // ---- helpers ----

    private void finish(boolean success, String message) {
        phase = Phase.FINISHED;
        vasyan.getNavigation().stop();
        vasyan.setFlying(false);
        if (fellMode) {
            // Never leave the pillar standing (quota reached / full inventory /
            // stall mid-felling): dismantle it so the landscape stays clean
            dismantlePillar();
        }
        exitFellMode();
        result = success ? ActionResult.success(message) : ActionResult.failure(message);
    }

    /**
     * Gives up on the CURRENT tree only (wedged climb/descent): dismantles
     * our pillar, blacklists the tree's remaining logs so SEARCH never
     * re-picks this wedged tree, and resumes the gather run. Unlike
     * finish(), the action itself keeps going until quota/timeout.
     */
    private void abandonTree(String reason) {
        debugLog("FELL", reason + " - abandoning tree (" + fellLogs.size() + " logs left)");
        dismantlePillar();
        unreachableTargets.addAll(fellLogs);
        if (unreachableTargets.size() > UNREACHABLE_TARGETS_LIMIT) {
            unreachableTargets.clear(); // keep the set bounded
        }
        exitFellMode();
        phase = Phase.SEARCH;
    }

    /**
     * Removes the pillar blocks under the Vasyan, dropping down level by level,
     * then wipes any pillar blocks left standing anywhere (mid-descent abort,
     * externally replaced blocks). Only positions in fellPillar are touched -
     * never the terrain and never the tree's own logs. Drops are picked up by
     * the vacuum, so nothing is left in the landscape.
     */
    private void dismantlePillar() {
        int guard = 0;
        while (fellHeight > 0 && guard++ < FELL_MAX_HEIGHT) {
            BlockPos below = vasyan.blockPosition().below();
            if (fellPillar.contains(below)) {
                vasyan.level().destroyBlock(below, true);
                fellPillar.remove(below);
            }
            vasyan.setPos(below.getX() + 0.5, below.getY(), below.getZ() + 0.5);
            fellHeight--;
        }
        fellHeight = 0;
        for (BlockPos p : fellPillar) {
            if (!vasyan.level().getBlockState(p).isAir()) {
                vasyan.level().destroyBlock(p, true);
            }
        }
        fellPillar.clear();
    }

    private void debugLog(String type, String message) {
        ru.pravets.vasyan.debug.AgentDebugBuffer.log(vasyan.getVasyanName(), type, message);
    }

    /** Squared horizontal (XZ) distance to a block - for ground navigation checks. */
    private double horizontalDistanceSqr(BlockPos pos) {
        double dx = vasyan.getX() - (pos.getX() + 0.5);
        double dz = vasyan.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private void rememberUnreachable(BlockPos pos) {
        if (unreachableTargets.size() >= UNREACHABLE_TARGETS_LIMIT) {
            unreachableTargets.clear(); // keep the set bounded
        }
        unreachableTargets.add(pos);
    }

    /** Remembers a failed vertical pocket so gather does not retry every ore in the same pit. */
    private void rememberVerticalTrap(BlockPos pos) {
        int dy = Math.abs(pos.getY() - vasyan.blockPosition().getY());
        if (dy < 2) {
            return;
        }
        if (verticalTrapCenters.size() >= UNREACHABLE_TARGETS_LIMIT) {
            verticalTrapCenters.remove(0); // bounded FIFO; older traps become eligible again
        }
        verticalTrapCenters.add(pos.immutable());
    }

    /** Whether {@code pos} belongs to a recently failed vertical pocket. */
    private boolean isInVerticalTrap(BlockPos pos) {
        BlockPos botPos = vasyan.blockPosition();
        Iterator<BlockPos> it = verticalTrapCenters.iterator();
        while (it.hasNext()) {
            BlockPos center = it.next();
            int botHorizontal = Math.max(Math.abs(botPos.getX() - center.getX()),
                Math.abs(botPos.getZ() - center.getZ()));
            if (botHorizontal > 32) {
                it.remove(); // stale trap far behind the bot
                continue;
            }
            int horizontal = Math.max(Math.abs(pos.getX() - center.getX()),
                Math.abs(pos.getZ() - center.getZ()));
            int vertical = Math.abs(pos.getY() - center.getY());
            if (horizontal <= VERTICAL_TRAP_HORIZONTAL_RADIUS
                    && vertical <= VERTICAL_TRAP_VERTICAL_RADIUS) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onCancel() {
        vasyan.getNavigation().stop();
        vasyan.setFlying(false);
        if (fellMode) {
            // Task cancelled mid-felling: dismantle the pillar before leaving
            dismantlePillar();
        }
    }

    @Override
    public String getDescription() {
        return "Gather " + targetQuantity + " " + resourceLabel()
            + " (" + gatheredCount + " found)";
    }
}
