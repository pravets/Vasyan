package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Target;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VasyanPathFinderTest {
    @Test
    void reconstructsMixedTransitionsAndKeepsSameCoordinateAlternatives() {
        Node start = node(0, 64, 0), walked = node(1, 64, 0), dug = node(2, 64, 0);
        Node placed = node(3, 64, 0), pillared = node(3, 65, 0);
        VasyanEdge walk = edge(start, walked, MoveType.WALK, 1, null, null, null);
        VasyanEdge dig = edge(walked, dug, MoveType.DIG, 4, new BlockPos(2, 64, 0), new BlockPos(2, 65, 0), null);
        VasyanEdge place = edge(dug, placed, MoveType.PLACE, 3, null, null, new BlockPos(3, 63, 0));
        VasyanEdge pillar = edge(placed, pillared, MoveType.PILLAR_UP, 2, null, null, new BlockPos(3, 64, 0));
        VasyanNodeEvaluator evaluator = new FixtureEvaluator(start, Map.of(start, List.of(walk), walked, List.of(dig),
            dug, List.of(place), placed, List.of(pillar)));

        VasyanPath path = (VasyanPath) new VasyanPathFinder(evaluator, 100)
            .findPath(null, null, Set.of(pillared.asBlockPos()), 32, 0, 1.0F);

        assertEquals(List.of(MoveType.WALK, MoveType.DIG, MoveType.PLACE, MoveType.PILLAR_UP),
            path.transitions().stream().map(VasyanEdge::moveType).toList());
        assertEquals(new BlockPos(2, 64, 0), path.transitions().get(1).digFoot());
    }

    @Test
    void walkWinsOverDigWhenBothEndAtSameCoordinate() {
        Node start = node(0, 64, 0), walkNode = node(1, 64, 0), digNode = node(1, 64, 0);
        VasyanEdge walk = edge(start, walkNode, MoveType.WALK, 1, null, null, null);
        VasyanEdge dig = edge(start, digNode, MoveType.DIG, 5, new BlockPos(1, 64, 0), new BlockPos(1, 65, 0), null);
        VasyanNodeEvaluator evaluator = new FixtureEvaluator(start, Map.of(start, List.of(dig, walk)));

        VasyanPath path = (VasyanPath) new VasyanPathFinder(evaluator, 100)
            .findPath(null, null, Set.of(walkNode.asBlockPos()), 32, 0, 1.0F);

        assertEquals(MoveType.WALK, path.transitions().get(0).moveType());
    }

    @Test
    void cheaperLongerWalkRouteBeatsShortExpensiveDigRoute() {
        Node start = node(0, 64, 0), shortGoal = node(2, 64, 0), step = node(1, 64, 0);
        Node longGoal = node(2, 64, 0);
        VasyanEdge dig = edge(start, shortGoal, MoveType.DIG, 10, new BlockPos(1, 64, 0),
            new BlockPos(1, 65, 0), null);
        VasyanEdge firstWalk = edge(start, step, MoveType.WALK, 1, null, null, null);
        VasyanEdge secondWalk = edge(step, longGoal, MoveType.WALK, 1, null, null, null);
        VasyanNodeEvaluator evaluator = new FixtureEvaluator(start, Map.of(start, List.of(dig, firstWalk),
            step, List.of(secondWalk)));

        VasyanPath path = (VasyanPath) new VasyanPathFinder(evaluator, 100)
            .findPath(null, null, Set.of(shortGoal.asBlockPos()), 32, 0, 1.0F);

        assertEquals(List.of(MoveType.WALK, MoveType.WALK),
            path.transitions().stream().map(VasyanEdge::moveType).toList());
    }

    @Test
    void coordinateEqualStatesWithDifferentMetadataRemainSearchable() {
        Node start = node(0, 64, 0), sharedWalk = node(1, 64, 0), sharedDig = node(1, 64, 0);
        Node goal = node(2, 64, 0);
        VasyanEdge walk = edge(start, sharedWalk, MoveType.WALK, 1, null, null, null);
        VasyanEdge dig = edge(start, sharedDig, MoveType.DIG, 2, new BlockPos(1, 64, 0),
            new BlockPos(1, 65, 0), null);
        VasyanEdge fromWalk = edge(sharedWalk, goal, MoveType.PLACE, 1, null, null, new BlockPos(2, 63, 0));
        VasyanEdge fromDig = edge(sharedDig, goal, MoveType.PILLAR_UP, 1, null, null, new BlockPos(1, 64, 0));
        Map<Node, List<VasyanEdge>> edges = new IdentityHashMap<>();
        edges.put(start, List.of(walk, dig));
        edges.put(sharedWalk, List.of(fromWalk));
        edges.put(sharedDig, List.of(fromDig));
        VasyanNodeEvaluator evaluator = new FixtureEvaluator(start, edges);

        VasyanPath path = (VasyanPath) new VasyanPathFinder(evaluator, 100)
            .findPath(null, null, Set.of(goal.asBlockPos()), 32, 0, 1.0F);

        assertEquals(List.of(MoveType.WALK, MoveType.PLACE),
            path.transitions().stream().map(VasyanEdge::moveType).toList());
    }

    @Test
    void clearsNavigationTargetAfterEachSearch() {
        Node start = node(0, 64, 0), goal = node(1, 64, 0);
        FixtureEvaluator evaluator = new FixtureEvaluator(start,
            Map.of(start, List.of(edge(start, goal, MoveType.WALK, 1, null, null, null))));
        VasyanPathFinder pathFinder = new VasyanPathFinder(evaluator, 100);

        pathFinder.findPath(null, null, Set.of(goal.asBlockPos()), 32, 0, 1.0F);
        assertNull(evaluator.navigationTarget);

        pathFinder.findPath(null, null, Set.of(), 32, 0, 1.0F);
        assertNull(evaluator.navigationTarget);
    }

    private static Node node(int x, int y, int z) { return new Node(x, y, z); }
    private static VasyanEdge edge(Node from, Node to, MoveType type, float cost, BlockPos foot, BlockPos head, BlockPos place) {
        return new VasyanEdge(from, to, type, cost, foot, head, place);
    }

    private static final class FixtureEvaluator extends VasyanNodeEvaluator {
        private final Node start;
        private final Map<Node, List<VasyanEdge>> edges;
        private BlockPos navigationTarget;
        FixtureEvaluator(Node start, Map<Node, List<VasyanEdge>> edges) { super(null, null); this.start = start; this.edges = edges; }
        @Override public void setNavigationTarget(BlockPos target) { this.navigationTarget = target; }
        @Override public Node getStart() { return start; }
        @Override public void prepare(PathNavigationRegion region, Mob mob) { }
        @Override public void done() { }
        @Override public Target getGoal(double x, double y, double z) { return new Target((int) x, (int) y, (int) z); }
        @Override public List<VasyanEdge> getEdges(PathNavigationRegion region, Node current) { return edges.getOrDefault(current, List.of()); }
        @Override public BlockPathTypes getBlockPathType(net.minecraft.world.level.BlockGetter level, int x, int y, int z, Mob mob) { return BlockPathTypes.OPEN; }
        @Override public BlockPathTypes getBlockPathType(net.minecraft.world.level.BlockGetter level, int x, int y, int z) { return BlockPathTypes.OPEN; }
    }
}
