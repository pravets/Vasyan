package ru.pravets.vasyan.navigation;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jetbrains.annotations.Nullable;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;

/**
 * Path node evaluator that extends vanilla walking with three extra edge kinds:
 * digging straight through a one-cell wall, bridging a gap that is too deep to
 * drop into, and pillaring up a short cliff. Vanilla neighbors are always
 * generated first and the special edges are additional options, so A* keeps
 * preferring a cheap walk whenever one exists.
 *
 * <p>Every generated node records its {@link MoveType} in a map keyed by the
 * node coordinate hash (see {@link Node#hashCode()}); the navigation layer
 * reads it back through {@link #getMoveType(Node)} to execute the edge. The
 * map is cleared in {@link #prepare(PathNavigationRegion, Mob)}, which vanilla
 * calls at the start of every path computation, so evaluator instances stay
 * safe to reuse; pathfinding runs on the server thread only.</p>
 *
 * <p>Dig/place helpers need a real {@link Level} (block destroy speeds, fluid
 * states, scaffold shape checks); the {@link PathNavigationRegion} vanilla
 * navigates on does not implement it, hence the separately stored world.</p>
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public class VasyanNodeEvaluator extends WalkNodeEvaluator {

    private final Level world;
    private final Int2ObjectMap<MoveType> moveTypes = new Int2ObjectOpenHashMap<>();

    public VasyanNodeEvaluator(Mob mob, Level level) {
        this.mob = mob;
        this.world = level;
    }

    /**
     * Move type recorded for {@code node}; {@link MoveType#WALK} for nodes the
     * evaluator never generated (including every node produced by vanilla).
     */
    public MoveType getMoveType(Node node) {
        return moveTypes.getOrDefault(node.hashCode(), MoveType.WALK);
    }

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        this.mob = mob;
        moveTypes.clear();
    }

    @Override
    public int getNeighbors(Node[] neighbors, Node current) {
        int count = super.getNeighbors(neighbors, current);
        return addSpecialEdges(neighbors, count, current);
    }

    /**
     * Appends DIG/PLACE/PILLAR_UP neighbors of {@code current} to
     * {@code neighbors} starting at {@code count} and returns the new count.
     * At most one DIG and one PLACE edge per horizontal direction and one
     * PILLAR_UP edge overall, keeping the node fan-out bounded.
     * Package-visible so tests can drive it without a PathNavigationRegion.
     */
    int addSpecialEdges(Node[] neighbors, int count, Node current) {
        BlockPos pos = current.asBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            count = tryDigEdge(neighbors, count, pos, direction);
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            count = tryPlaceEdge(neighbors, count, pos, direction);
        }
        return tryPillarUpEdge(neighbors, count, pos);
    }

    /**
     * DIG edge: the foot and/or head cell one step in {@code direction} is a
     * safe breakable block and the column beyond it is open. The edge lands
     * beyond the obstacle; the cost prices the (first) breakable obstacle cell.
     */
    private int tryDigEdge(Node[] neighbors, int count, BlockPos pos, Direction direction) {
        BlockPos foot = pos.relative(direction);
        BlockPos head = foot.above();
        boolean footDig = canDig(foot);
        boolean headDig = canDig(head);
        if (!footDig && !headDig) {
            return count;
        }
        BlockPos beyond = foot.relative(direction);
        if (DigRules.isBreakable(world, beyond, false) || DigRules.isBreakable(world, beyond.above(), false)) {
            return count;
        }
        Node node = getNode(beyond.getX(), beyond.getY(), beyond.getZ());
        if (contains(neighbors, count, node) || count >= neighbors.length) {
            return count;
        }
        node.costMalus += DigPlaceCosts.digCost(world, footDig ? foot : head);
        node.type = BlockPathTypes.WALKABLE;
        moveTypes.put(node.hashCode(), MoveType.DIG);
        neighbors[count] = node;
        return count + 1;
    }

    /**
     * PLACE edge: the foot cell one step in {@code direction} is open but the
     * column below it falls more than {@code navigation.maxDropDown} blocks
     * before solid ground, and the bot carries a scaffold block. The edge lands
     * on the near side of the gap; the navigation layer places the scaffold.
     */
    private int tryPlaceEdge(Node[] neighbors, int count, BlockPos pos, Direction direction) {
        BlockPos foot = pos.relative(direction);
        if (!isOpen(foot)) {
            return count;
        }
        int maxDropDown = VasyanConfig.NAV_MAX_DROP_DOWN.get();
        int drop = 0;
        BlockPos below = foot.below();
        while (drop <= maxDropDown && isOpen(below)) {
            drop++;
            below = below.below();
        }
        if (drop <= maxDropDown) {
            return count;
        }
        if (scaffoldAt(foot) == null) {
            return count;
        }
        Node node = getNode(foot.getX(), foot.getY(), foot.getZ());
        if (contains(neighbors, count, node) || count >= neighbors.length) {
            return count;
        }
        node.costMalus += DigPlaceCosts.placeCost();
        node.type = BlockPathTypes.WALKABLE;
        moveTypes.put(node.hashCode(), MoveType.PLACE);
        neighbors[count] = node;
        return count + 1;
    }

    /**
     * PILLAR_UP edge: the two cells directly above are open and the bot
     * carries a scaffold block. The edge lands one block up in the same column;
     * the navigation layer places the pillar block under the bot.
     */
    private int tryPillarUpEdge(Node[] neighbors, int count, BlockPos pos) {
        BlockPos above = pos.above();
        if (!isOpen(above) || !isOpen(above.above())) {
            return count;
        }
        if (scaffoldAt(above) == null) {
            return count;
        }
        Node node = getNode(above.getX(), above.getY(), above.getZ());
        if (contains(neighbors, count, node) || count >= neighbors.length) {
            return count;
        }
        node.costMalus += DigPlaceCosts.pillarUpCost();
        node.type = BlockPathTypes.WALKABLE;
        moveTypes.put(node.hashCode(), MoveType.PILLAR_UP);
        neighbors[count] = node;
        return count + 1;
    }

    /** Whether the block at {@code pos} may be broken and doing so is safe. */
    private boolean canDig(BlockPos pos) {
        return DigRules.isBreakable(world, pos, false)
            && !DigRules.wouldCreateFlow(world, pos)
            && !DigRules.isFallingBlock(world, pos);
    }

    /** Whether the bot carries a block it can place as scaffold at {@code refPos}. */
    @Nullable
    private ItemStack scaffoldAt(BlockPos refPos) {
        if (!(this.mob instanceof VasyanEntity vasyan)) {
            return null;
        }
        return ScaffoldBlocks.findBestStack(vasyan.getInventory(), world, refPos);
    }

    /** Whether the cell is passable air, liquid or a replaceable block. */
    private boolean isOpen(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.canBeReplaced() || isLiquid(state.getFluidState());
    }

    /** Whether the given fluid state is water or lava. */
    private static boolean isLiquid(FluidState fluid) {
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA);
    }

    private static boolean contains(Node[] neighbors, int count, Node node) {
        for (int i = 0; i < count; i++) {
            if (neighbors[i] == node) {
                return true;
            }
        }
        return false;
    }
}
