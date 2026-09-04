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

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static Node node(int x, int y, int z) { return new Node(x, y, z); }
    private static VasyanEdge edge(Node from, Node to, MoveType type, float cost, BlockPos foot, BlockPos head, BlockPos place) {
        return new VasyanEdge(from, to, type, cost, foot, head, place);
    }

    private static final class FixtureEvaluator extends VasyanNodeEvaluator {
        private final Node start;
        private final Map<Node, List<VasyanEdge>> edges;
        FixtureEvaluator(Node start, Map<Node, List<VasyanEdge>> edges) { super(null, null); this.start = start; this.edges = edges; }
        @Override public Node getStart() { return start; }
        @Override public void prepare(PathNavigationRegion region, Mob mob) { }
        @Override public void done() { }
        @Override public Target getGoal(double x, double y, double z) { return new Target((int) x, (int) y, (int) z); }
        @Override public List<VasyanEdge> getEdges(PathNavigationRegion region, Node current) { return edges.getOrDefault(current, List.of()); }
        @Override public BlockPathTypes getBlockPathType(net.minecraft.world.level.BlockGetter level, int x, int y, int z, Mob mob) { return BlockPathTypes.OPEN; }
        @Override public BlockPathTypes getBlockPathType(net.minecraft.world.level.BlockGetter level, int x, int y, int z) { return BlockPathTypes.OPEN; }
    }
}
