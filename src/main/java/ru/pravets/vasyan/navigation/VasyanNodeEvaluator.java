package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jetbrains.annotations.Nullable;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Path node evaluator that extends vanilla walking with three extra edge kinds:
 * digging straight through a one-cell wall, bridging a gap that is too deep to
 * drop into, and pillaring up a short cliff. Vanilla neighbors are always
 * generated first and the special edges are additional options, so A* keeps
 * preferring a cheap walk whenever one exists.
 *
 * <p>Dig/place helpers need a real {@link Level} (block destroy speeds, fluid
 * states, scaffold shape checks); the {@link PathNavigationRegion} vanilla
 * navigates on does not implement it, hence the separately stored world.</p>
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public class VasyanNodeEvaluator extends WalkNodeEvaluator {

    private final Level world;

    public VasyanNodeEvaluator(Mob mob, Level level) {
        this.mob = mob;
        this.world = level;
    }

    @Override
    public int getNeighbors(Node[] neighbors, Node current) {
        int count = super.getNeighbors(neighbors, current);
        for (VasyanEdge edge : getSpecialEdges(current)) {
            if (!contains(neighbors, count, edge.to()) && count < neighbors.length) {
                neighbors[count++] = edge.to();
            }
        }
        return count;
    }

    /** Returns special transition candidates for an unprepared evaluator. */
    public List<VasyanEdge> getEdges(Node current) {
        return getSpecialEdges(current);
    }

    /** Returns vanilla and special transition candidates after {@link #prepare} has been called. */
    public List<VasyanEdge> getEdges(PathNavigationRegion region, Node current) {
        Node[] neighbors = new Node[32];
        int count = super.getNeighbors(neighbors, current);
        return getEdges(current, neighbors, count);
    }

    List<VasyanEdge> getEdges(Node current, Node[] vanillaNeighbors, int vanillaCount) {
        List<VasyanEdge> edges = new ArrayList<>(vanillaCount + 9);
        for (int i = 0; i < vanillaCount; i++) {
            edges.add(new VasyanEdge(current, vanillaNeighbors[i], MoveType.WALK, DigPlaceCosts.walkCost(),
                null, null, null));
        }
        edges.addAll(getSpecialEdges(current));
        return List.copyOf(edges);
    }

    /** Compatibility adapter for callers that only need coordinate neighbors. */
    int addSpecialEdges(Node[] neighbors, int count, Node current) {
        for (VasyanEdge edge : getSpecialEdges(current)) {
            if (!contains(neighbors, count, edge.to()) && count < neighbors.length) {
                neighbors[count++] = edge.to();
            }
        }
        return count;
    }

    private List<VasyanEdge> getSpecialEdges(Node current) {
        List<VasyanEdge> edges = new ArrayList<>(9);
        BlockPos pos = current.asBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            tryDigEdge(edges, current, pos, direction);
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            tryPlaceEdge(edges, current, pos, direction);
        }
        tryPillarUpEdge(edges, current, pos);
        return edges;
    }

    /**
     * DIG edge: the foot cell one step in {@code direction} is a safe breakable
     * block, the head cell is breakable the same way or already passable, and the
     * two cells beyond the obstacle are passable (open and not lava). The edge
     * lands beyond the obstacle and prices the foot dig; the navigation layer
     * clears any head block too.
     */
    private void tryDigEdge(List<VasyanEdge> edges, Node current, BlockPos pos, Direction direction) {
        BlockPos foot = pos.relative(direction);
        BlockPos head = foot.above();
        if (!canDig(foot) || !(canDig(head) || isPassable(head))) {
            return;
        }
        BlockPos beyond = foot.relative(direction);
        if (!isPassable(beyond) || !isPassable(beyond.above())) {
            return;
        }
        Node node = getNode(beyond.getX(), beyond.getY(), beyond.getZ());
        edges.add(new VasyanEdge(current, node, MoveType.DIG, DigPlaceCosts.digCost(world, foot), foot, head, null));
    }

    /**
     * PLACE edge: the foot cell one step in {@code direction} is open but the
     * column below it falls more than {@code navigation.maxDropDown} blocks
     * before solid ground, and the bot carries a scaffold block. The edge lands
     * on the near side of the gap; the navigation layer places the scaffold.
     */
    private void tryPlaceEdge(List<VasyanEdge> edges, Node current, BlockPos pos, Direction direction) {
        BlockPos foot = pos.relative(direction);
        if (!isOpen(foot)) {
            return;
        }
        int maxDropDown = VasyanConfig.NAV_MAX_DROP_DOWN.get();
        int drop = 0;
        BlockPos below = foot.below();
        while (drop <= maxDropDown && isOpen(below)) {
            drop++;
            below = below.below();
        }
        if (drop <= maxDropDown) {
            return;
        }
        if (scaffoldAt(foot) == null) {
            return;
        }
        Node node = getNode(foot.getX(), foot.getY(), foot.getZ());
        edges.add(new VasyanEdge(current, node, MoveType.PLACE, DigPlaceCosts.placeCost(), null, null, foot.below()));
    }

    /**
     * PILLAR_UP edge: the two cells directly above are open and the bot
     * carries a scaffold block. The edge lands one block up in the same column;
     * the navigation layer places the pillar block under the bot.
     */
    private void tryPillarUpEdge(List<VasyanEdge> edges, Node current, BlockPos pos) {
        BlockPos above = pos.above();
        if (!isOpen(above) || !isOpen(above.above())) {
            return;
        }
        if (scaffoldAt(above) == null) {
            return;
        }
        Node node = getNode(above.getX(), above.getY(), above.getZ());
        edges.add(new VasyanEdge(current, node, MoveType.PILLAR_UP, DigPlaceCosts.pillarUpCost(), null, null, pos));
    }

    private static boolean contains(Node[] neighbors, int count, Node node) {
        for (int i = 0; i < count; i++) {
            if (neighbors[i] == node) {
                return true;
            }
        }
        return false;
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

    /** Whether a cell beyond a dug tunnel can actually be entered: open and not lava. */
    private boolean isPassable(BlockPos pos) {
        return isOpen(pos) && !world.getBlockState(pos).is(Blocks.LAVA);
    }

    /** Whether the given fluid state is water or lava. */
    private static boolean isLiquid(FluidState fluid) {
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA);
    }

}
