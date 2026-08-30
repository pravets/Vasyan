package ru.pravets.vasyan.memory;

import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Honest vision for Vasyan: scans the world for interesting blocks (trees, ores,
 * chests) but only reports blocks with a clear line of sight (no x-ray, no cheats).
 *
 * <p>Scanning is <b>approximate sampling</b> beyond the precise near zone:
 * the near zone ({@value #PRECISE_RADIUS} blocks) is scanned block-by-block,
 * the far zone uses the configured grid step. The cache is per-Vasyan and is
 * invalidated when Vasyan moves, changes dimension, or the TTL expires.</p>
 *
 * <p>Performance safeguards: positions in unloaded chunks are skipped (no
 * synchronous chunk loading), and the effective grid step is automatically
 * increased so a single scan never exceeds {@value #MAX_SCAN_POSITIONS} block
 * lookups (prevents server tick stalls with large radius / small step).</p>
 */
public final class VisionScanner {

    /** Max block lookups per full scan (keeps server tick responsive). */
    private static final int MAX_SCAN_POSITIONS = 100_000;

    /** Radius (blocks) scanned block-by-block for exact results. */
    private static final int PRECISE_RADIUS = 16;

    /** Block types Vasyan considers "interesting" when looking around. */
    private static final Set<Block> INTERESTING = new HashSet<>();

    static {
        // Trees (logs)
        INTERESTING.add(Blocks.OAK_LOG);
        INTERESTING.add(Blocks.BIRCH_LOG);
        INTERESTING.add(Blocks.SPRUCE_LOG);
        INTERESTING.add(Blocks.JUNGLE_LOG);
        INTERESTING.add(Blocks.ACACIA_LOG);
        INTERESTING.add(Blocks.DARK_OAK_LOG);
        INTERESTING.add(Blocks.MANGROVE_LOG);
        INTERESTING.add(Blocks.CHERRY_LOG);
        INTERESTING.add(Blocks.CRIMSON_STEM);
        INTERESTING.add(Blocks.WARPED_STEM);

        // Ores (overworld)
        INTERESTING.add(Blocks.COAL_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_COAL_ORE);
        INTERESTING.add(Blocks.IRON_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_IRON_ORE);
        INTERESTING.add(Blocks.COPPER_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_COPPER_ORE);
        INTERESTING.add(Blocks.GOLD_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_GOLD_ORE);
        INTERESTING.add(Blocks.REDSTONE_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_REDSTONE_ORE);
        INTERESTING.add(Blocks.LAPIS_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_LAPIS_ORE);
        INTERESTING.add(Blocks.DIAMOND_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_DIAMOND_ORE);
        INTERESTING.add(Blocks.EMERALD_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_EMERALD_ORE);

        // Ores (nether)
        INTERESTING.add(Blocks.NETHER_GOLD_ORE);
        INTERESTING.add(Blocks.NETHER_QUARTZ_ORE);
        INTERESTING.add(Blocks.ANCIENT_DEBRIS);

        // Plants (surface)
        INTERESTING.add(Blocks.SUGAR_CANE);

        // Storage
        INTERESTING.add(Blocks.CHEST);
        INTERESTING.add(Blocks.TRAPPED_CHEST);
        INTERESTING.add(Blocks.BARREL);
    }

    private record ScanCache(long cachedAtTick, BlockPos origin, ResourceKey<Level> dimension,
                             Map<Block, List<BlockPos>> visible) {}

    private static final Map<VasyanEntity, ScanCache> CACHE = new ConcurrentHashMap<>();

    private VisionScanner() {}

    /**
     * Finds all visible blocks of the given type near Vasyan, nearest first.
     * Returns an empty list if nothing is visible.
     */
    public static List<BlockPos> findVisible(VasyanEntity vasyan, Block target) {
        if (target == null) {
            return List.of();
        }
        return findVisible(vasyan, Set.of(target));
    }

    /**
     * Finds all visible blocks of any of the given types near Vasyan, nearest first.
     * Returns an empty list if nothing is visible.
     *
     * <p>Requested blocks that are not in the shared {@link #INTERESTING} cache are
     * scanned on demand with the same radius/step budget, so common blocks like
     * stone or cobblestone are still discoverable without polluting the shared cache.
     */
    public static List<BlockPos> findVisible(VasyanEntity vasyan, Set<Block> targets) {
        Map<Block, List<BlockPos>> visible = scan(vasyan);
        List<BlockPos> found = new ArrayList<>();
        Set<Block> missing = new HashSet<>();
        for (Block block : targets) {
            List<BlockPos> positions = visible.get(block);
            if (positions != null && !positions.isEmpty()) {
                found.addAll(positions);
            } else if (!INTERESTING.contains(block)) {
                missing.add(block);
            }
        }
        if (!missing.isEmpty()) {
            found.addAll(scanTargets(vasyan, missing));
        }
        BlockPos center = vasyan.blockPosition();
        return found.stream()
            .sorted(Comparator.comparingDouble(p -> p.distSqr(center)))
            .toList();
    }

    /**
     * Finds visible blocks of ANY log type (BlockTags.LOGS): used by the
     * "gather wood" mode where the bot must chop birch, spruce etc., not just
     * the single oak type the LLM happened to name.
     */
    public static List<BlockPos> findVisibleAnyLog(VasyanEntity vasyan) {
        Map<Block, List<BlockPos>> visible = scan(vasyan);
        List<BlockPos> found = new ArrayList<>();
        for (Map.Entry<Block, List<BlockPos>> entry : visible.entrySet()) {
            if (entry.getKey().builtInRegistryHolder().is(BlockTags.LOGS)) {
                found.addAll(entry.getValue());
            }
        }
        BlockPos center = vasyan.blockPosition();
        return found.stream()
            .sorted(Comparator.comparingDouble(p -> p.distSqr(center)))
            .toList();
    }

    /**
     * Brute-force scan for blocks of a given type within a cube around the
     * bot, WITHOUT line-of-sight checks. In a forest the view ray gets
     * blocked by other trunks and dense canopies, so the bot could stand
     * next to trees and never "see" them - this scan finds blocks it can
     * walk to directly.
     *
     * @param target the exact block to look for, or null for ANY log type
     */
    public static List<BlockPos> findNearbyBlocks(VasyanEntity vasyan, int radius, Block target) {
        Set<Block> targets = target == null ? null : Set.of(target);
        return findNearbyBlocks(vasyan, radius, targets);
    }

    /**
     * Brute-force scan for blocks of any of the given types within a cube
     * around the bot, WITHOUT line-of-sight checks.
     *
     * @param targets the set of blocks to look for, or null for ANY log type
     */
    public static List<BlockPos> findNearbyBlocks(VasyanEntity vasyan, int radius, Set<Block> targets) {
        BlockPos center = vasyan.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        Level level = vasyan.level();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    // Never load chunks synchronously on the server tick
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    Block block = level.getBlockState(pos).getBlock();
                    boolean match = targets == null
                        ? block.builtInRegistryHolder().is(BlockTags.LOGS)
                        : targets.contains(block);
                    if (match) {
                        found.add(pos);
                    }
                }
            }
        }
        return found.stream()
            .sorted(Comparator.comparingDouble(p -> p.distSqr(center)))
            .toList();
    }

    /**
     * No-LOS nearby scan for ORES that keeps anti-xray honest: a block is
     * returned only when it is exposed ({@link #isExposedForMining}) AND has a
     * passable approach cell next to it ({@link #hasPassableApproach}). This
     * finds the exposed coal face "just around the corner" that the eye ray
     * misses against a terrain lip, while buried ore - or ore reachable only
     * through solid ground - stays invisible.
     */
    public static List<BlockPos> findNearbyExposedBlocks(VasyanEntity vasyan, int radius,
                                                         Set<Block> targets) {
        Level level = vasyan.level();
        return findNearbyBlocks(vasyan, radius, targets).stream()
            .filter(p -> isExposedForMining(level, p, level.getBlockState(p).getBlock()))
            .filter(p -> hasPassableApproach(level, p))
            .toList();
    }

    /**
     * Whether the bot can physically reach an exposed face of {@code pos}:
     * some adjacent cell is passable (air/leaves) with passable headroom, so
     * the bot can stand there and gets a trivial line of sight to the block.
     * Standing on top of the block counts (UP approach); the DOWN approach
     * rejects itself because its headroom cell is the block itself.
     */
    static boolean hasPassableApproach(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos approach = pos.relative(dir);
            if (!isPassableForVision(level.getBlockState(approach))) {
                continue;
            }
            if (!isPassableForVision(level.getBlockState(approach.above()))) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Leaves are transparent/passable for vision purposes, everything solid is not. */
    private static boolean isPassableForVision(BlockState state) {
        return !state.isSolid() || state.getBlock() instanceof LeavesBlock;
    }

    /**
     * Finds the nearest visible block of the given type, or null.
     */
    public static BlockPos findNearestVisible(VasyanEntity vasyan, Block target) {
        List<BlockPos> found = findVisible(vasyan, target);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Human-readable summary of what Vasyan can see, for the LLM prompt.
     * Example: "oak_log x3 (12m S), iron_ore (8m down), chest (20m W)"
     * Block names are registry ids (oak_log), matching what actions expect.
     */
    public static String getVisibleSummary(VasyanEntity vasyan) {
        Map<Block, List<BlockPos>> visible = scan(vasyan);
        if (visible.isEmpty()) {
            return "nothing interesting";
        }

        BlockPos center = vasyan.blockPosition();
        List<String> parts = new ArrayList<>();

        visible.entrySet().stream()
            .sorted((a, b) -> {
                BlockPos na = VisionUtils.nearestOf(a.getValue(), center);
                BlockPos nb = VisionUtils.nearestOf(b.getValue(), center);
                return Double.compare(center.distSqr(na), center.distSqr(nb));
            })
            .limit(8)
            .forEach(entry -> {
                Block block = entry.getKey();
                List<BlockPos> positions = entry.getValue();
                BlockPos nearest = VisionUtils.nearestOf(positions, center);
                int distance = (int) Math.round(Math.sqrt(center.distSqr(nearest)));
                String direction = VisionUtils.directionTo(center, nearest);
                parts.add(blockId(block) + " x" + positions.size()
                    + " (" + distance + "m " + direction + ")");
            });

        return String.join(", ", parts);
    }

    /**
     * Checks whether Vasyan has a clear line of sight to the given block.
     * Leaves are treated as transparent for the sight ray (a trunk hidden
     * behind the canopy is still a valid target) - everything else with a
     * collision blocks the view.
     */
    public static boolean hasLineOfSight(VasyanEntity vasyan, BlockPos target) {
        Level level = vasyan.level();
        Vec3 eye = vasyan.getEyePosition(1.0F);
        Vec3 to = Vec3.atCenterOf(target);
        Vec3 dir = to.subtract(eye);
        if (dir.lengthSqr() < 1.0E-4) {
            return true; // target is inside/at the eye - trivially visible
        }
        dir = dir.normalize();

        // Step through leaves (they have a collision shape but should not
        // hide ores/logs behind the canopy); hard cap on iterations.
        for (int i = 0; i < 16; i++) {
            BlockHitResult hit = level.clip(new ClipContext(eye, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vasyan));

            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
            // Hit the target itself, or something at/behind it -> visible
            if (hit.getBlockPos().equals(target)) {
                return true;
            }
            if (eye.distanceToSqr(hit.getLocation()) >= eye.distanceToSqr(to) - 0.5) {
                return true;
            }
            BlockState hitState = level.getBlockState(hit.getBlockPos());
            if (hitState.getBlock() instanceof LeavesBlock) {
                // leaves are transparent: continue the ray just past them
                eye = hit.getLocation().add(dir.scale(0.2));
                continue;
            }
            return false;
        }
        return false;
    }

    /**
     * Line of sight for a mining candidate: ray to the block CENTER first; if
     * that clips a terrain lip, try a ray to the center of each already-open
     * (non-solid or leaves) neighbor cell. An ore exposed on a wall just above
     * the bot is visible through its open face even when the single center ray
     * grazes the shaft edge (Alex' pit-wall coal). Buried ore gains nothing:
     * with no open neighbor there is no extra ray, so anti-xray stays intact.
     */
    public static boolean hasLineOfSightForMining(VasyanEntity vasyan, BlockPos target, Block block) {
        if (hasLineOfSight(vasyan, target)) {
            return true;
        }
        if (block.builtInRegistryHolder().is(BlockTags.LOGS)) {
            return false; // logs: thin trunk through canopy relies on the center ray
        }
        Level level = vasyan.level();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = target.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            if (neighborState.isSolid() && !(neighborState.getBlock() instanceof LeavesBlock)) {
                continue; // face not open - no honest ray through it
            }
            if (hasLineOfSight(vasyan, neighbor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the cached scan for a Vasyan (call when the entity is removed/despawned).
     */
    public static void forget(VasyanEntity vasyan) {
        CACHE.remove(vasyan);
    }

    /**
     * Clears all cached scans (call on server shutdown).
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Returns the visible interesting blocks grouped by block type, nearest first.
     * Exposed for debug overlays and dump visualization.
     */
    public static Map<Block, List<BlockPos>> getVisibleBlocks(VasyanEntity vasyan) {
        return scan(vasyan);
    }

    private static Map<Block, List<BlockPos>> scan(VasyanEntity vasyan) {
        long tick = vasyan.level().getGameTime();
        BlockPos pos = vasyan.blockPosition();
        ResourceKey<Level> dim = vasyan.level().dimension();
        int ttl = VasyanConfig.WORLD_SCAN_CACHE_TICKS.get();

        ScanCache cached = CACHE.get(vasyan);
        if (cached != null && tick - cached.cachedAtTick < ttl
                && cached.origin().equals(pos) && cached.dimension() == dim) {
            return cached.visible();
        }

        Map<Block, List<BlockPos>> visible = scanWorld(vasyan);
        CACHE.put(vasyan, new ScanCache(tick, pos.immutable(), dim, visible));
        return visible;
    }

    /** Solid blocks that are not ores/logs require an exposed face to be visible.
     *  Logs are always considered exposed so whole-tree felling can find trunks
     *  hidden behind canopies; leaves stay transparent and do not block exposure. */
    public static boolean isExposedForMining(Level level, BlockPos pos, Block block) {
        if (block.builtInRegistryHolder().is(BlockTags.LOGS)) {
            return true; // whole-tree felling needs logs even when fully surrounded
        }
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState state = level.getBlockState(neighbor);
            if (!state.isSolid() || state.getBlock() instanceof LeavesBlock) {
                return true;
            }
        }
        return false;
    }

    private static Map<Block, List<BlockPos>> scanWorld(VasyanEntity vasyan) {
        Level level = vasyan.level();
        BlockPos center = vasyan.blockPosition();
        int radius = VasyanConfig.WORLD_SCAN_RADIUS.get();
        int configuredStep = Math.max(1, VasyanConfig.WORLD_SCAN_STEP.get());

        // Budget guard: auto-increase the effective XZ step so a full scan stays
        // within MAX_SCAN_POSITIONS block lookups (no server tick stalls).
        // The Y axis is always scanned at step 1: ore veins are vertically thin,
        // so a coarse Y step makes the bot blind to exposed faces above/below it.
        int step = effectiveStep(radius, configuredStep);

        Map<Block, Set<BlockPos>> candidates = new HashMap<>();
        collectCandidates(level, center, radius, step, candidates);
        collectVerticalColumn(level, center, radius, candidates);

        // Precise pass: block-by-block in the near zone so thin targets
        // (e.g. a single oak log trunk) are never missed close to Vasyan.
        // Set-based collection deduplicates positions that overlap between
        // the coarse and precise passes (no double raycasts, no inflated xN).
        if (step > 1) {
            int preciseRadius = Math.min(PRECISE_RADIUS, radius);
            collectCandidates(level, center, preciseRadius, 1, candidates);
        }

        // Line-of-sight check only for the candidates (usually a handful).
        // Cap the work per block type at the nearest 64 candidates to keep the
        // server tick fast even in dense forests.
        Map<Block, List<BlockPos>> visible = new HashMap<>();
        for (Map.Entry<Block, Set<BlockPos>> entry : candidates.entrySet()) {
            Block block = entry.getKey();
            boolean isLogTarget = block.builtInRegistryHolder().is(BlockTags.LOGS);
            List<BlockPos> positions = new ArrayList<>(entry.getValue());

            positions.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
            int checked = 0;
            for (BlockPos pos : positions) {
                if (checked >= 64) {
                    break;
                }
                checked++;
                if (isLogTarget || isExposedForMining(level, pos, block)) {
                    if (hasLineOfSightForMining(vasyan, pos, block)) {
                        visible.computeIfAbsent(block, k -> new ArrayList<>()).add(pos);
                    }
                }
            }
        }
        return visible;
    }

    private static List<BlockPos> scanTargets(VasyanEntity vasyan, Set<Block> targets) {
        Level level = vasyan.level();
        BlockPos center = vasyan.blockPosition();
        int radius = VasyanConfig.WORLD_SCAN_RADIUS.get();
        int configuredStep = Math.max(1, VasyanConfig.WORLD_SCAN_STEP.get());

        int step = effectiveStep(radius, configuredStep);

        Map<Block, Set<BlockPos>> candidates = new HashMap<>();
        collectTargets(level, center, radius, step, targets, candidates);
        collectVerticalColumn(level, center, radius, candidates);

        if (step > 1) {
            int preciseRadius = Math.min(PRECISE_RADIUS, radius);
            collectTargets(level, center, preciseRadius, 1, targets, candidates);
        }

        List<BlockPos> visible = new ArrayList<>();
        for (Map.Entry<Block, Set<BlockPos>> entry : candidates.entrySet()) {
            if (!targets.contains(entry.getKey())) {
                continue;
            }
            Set<BlockPos> positions = entry.getValue();
            List<BlockPos> sorted = new ArrayList<>(positions);
            sorted.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
            int checked = 0;
            for (BlockPos pos : sorted) {
                if (checked >= 64) {
                    break;
                }
                checked++;
                Block block = level.getBlockState(pos).getBlock();
                if (block.builtInRegistryHolder().is(BlockTags.LOGS)
                        || isExposedForMining(level, pos, block)) {
                    if (hasLineOfSightForMining(vasyan, pos, block)) {
                        visible.add(pos);
                    }
                }
            }
        }
        return visible;
    }

    private static void collectVerticalColumn(Level level, BlockPos center, int radius,
                                              Map<Block, Set<BlockPos>> candidates) {
        // Make sure blocks directly above/below the bot are never skipped by a
        // coarse grid step. This is cheap: just the center column.
        for (int dy = -radius; dy <= radius; dy++) {
            BlockPos pos = center.offset(0, dy, 0);
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            Block block = level.getBlockState(pos).getBlock();
            if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
                continue;
            }
            if (!INTERESTING.contains(block)) {
                continue;
            }
            candidates.computeIfAbsent(block, k -> new LinkedHashSet<>()).add(pos.immutable());
        }
    }

    /**
     * Effective horizontal step for one scan: starts at the configured step and
     * doubles until the lookup count fits MAX_SCAN_POSITIONS. The estimate is
     * XZ-area × full Y column, because the Y axis is always scanned at step 1 -
     * a coarse Y step would skip whole layers and hide exposed ore faces above
     * or below the bot (the "blind to a cliff-face vein" bug).
     */
    static int effectiveStep(int radius, int configuredStep) {
        int step = Math.max(1, configuredStep);
        while (step <= 8) {
            long horizontal = (2L * radius / step) + 1;
            long positions = horizontal * horizontal * ((2L * radius) + 1);
            if (positions <= MAX_SCAN_POSITIONS) {
                break;
            }
            step *= 2;
        }
        return step;
    }

    static void collectCandidates(Level level, BlockPos center, int radius, int step,
                                          Map<Block, Set<BlockPos>> candidates) {
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz += step) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    // Never load chunks synchronously on the server tick
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    Block block = level.getBlockState(pos).getBlock();
                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
                        continue;
                    }
                    if (!INTERESTING.contains(block)) {
                        continue;
                    }
                    candidates.computeIfAbsent(block, k -> new LinkedHashSet<>()).add(pos.immutable());
                }
            }
        }
    }

    private static void collectTargets(Level level, BlockPos center, int radius, int step,
                                       Set<Block> targets, Map<Block, Set<BlockPos>> candidates) {
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz += step) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    // Never load chunks synchronously on the server tick
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    Block block = level.getBlockState(pos).getBlock();
                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
                        continue;
                    }
                    if (!targets.contains(block)) {
                        continue;
                    }
                    candidates.computeIfAbsent(block, k -> new LinkedHashSet<>()).add(pos.immutable());
                }
            }
        }
    }

    private static String blockId(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null ? key.getPath() : block.toString();
    }
}
