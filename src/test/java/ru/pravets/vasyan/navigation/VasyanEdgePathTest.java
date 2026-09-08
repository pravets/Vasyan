package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.pathfinder.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VasyanEdgePathTest {

    @Test
    void mixedPathRetainsEachTransitionAndMutationPositions() {
        Node start = node(0, 64, 0);
        Node walked = node(1, 64, 0);
        Node dug = node(2, 64, 0);
        Node placed = node(3, 64, 0);
        Node pillared = node(3, 65, 0);
        VasyanEdge walk = edge(start, walked, MoveType.WALK, 1, null, null, null);
        VasyanEdge dig = edge(walked, dug, MoveType.DIG, 4, new BlockPos(2, 64, 0),
            new BlockPos(2, 65, 0), null);
        VasyanEdge place = edge(dug, placed, MoveType.PLACE, 3, null, null, new BlockPos(3, 63, 0));
        VasyanEdge pillar = edge(placed, pillared, MoveType.PILLAR_UP, 2, null, null,
            new BlockPos(3, 64, 0));

        VasyanPath path = new VasyanPath(List.of(start, walked, dug, placed, pillared),
            List.of(walk, dig, place, pillar), pillared.asBlockPos(), true);

        assertEquals(MoveType.WALK, path.getNextTransition().moveType());
        path.advance();
        assertEquals(MoveType.DIG, path.getNextTransition().moveType());
        assertEquals(new BlockPos(2, 64, 0), path.getNextTransition().digFoot());
        assertEquals(new BlockPos(2, 65, 0), path.getNextTransition().digHead());
        path.advance();
        assertEquals(MoveType.PLACE, path.getNextTransition().moveType());
        assertEquals(new BlockPos(3, 63, 0), path.getNextTransition().placePosition());
        path.advance();
        assertEquals(MoveType.PILLAR_UP, path.getNextTransition().moveType());
        assertEquals(new BlockPos(3, 64, 0), path.getNextTransition().placePosition());
    }

    @Test
    void sameDestinationCoordinateCanHaveDifferentEdgeTypes() {
        Node from = node(0, 64, 0);
        Node walkDestination = node(1, 64, 0);
        Node digDestination = node(1, 64, 0);

        VasyanEdge walk = edge(from, walkDestination, MoveType.WALK, 1, null, null, null);
        VasyanEdge dig = edge(from, digDestination, MoveType.DIG, 5, new BlockPos(1, 64, 0),
            new BlockPos(1, 65, 0), null);

        assertEquals(MoveType.WALK, walk.moveType());
        assertEquals(MoveType.DIG, dig.moveType());
        assertEquals(walkDestination.asBlockPos(), dig.to().asBlockPos());
    }

    @Test
    void duplicateCoordinateEntriesKeepDistinctTransitionsByPathIndex() {
        Node first = node(0, 64, 0);
        Node walkDestination = node(1, 64, 0);
        Node digDestination = node(1, 64, 0);
        VasyanPath path = new VasyanPath(List.of(first, walkDestination, digDestination),
            List.of(edge(first, walkDestination, MoveType.WALK, 1, null, null, null),
                edge(walkDestination, digDestination, MoveType.DIG, 5, new BlockPos(1, 64, 0),
                    new BlockPos(1, 65, 0), null)), digDestination.asBlockPos(), true);

        assertEquals(MoveType.WALK, path.getNextTransition().moveType());
        path.advance();
        assertEquals(MoveType.DIG, path.getNextTransition().moveType());
    }

    @Test
    void nextTransitionFollowsPathIndexAndEndsAfterLastStep() {
        Node first = node(0, 64, 0);
        Node second = node(1, 64, 0);
        VasyanPath path = new VasyanPath(List.of(first, second),
            List.of(edge(first, second, MoveType.WALK, 1, null, null, null)), second.asBlockPos(), true);

        assertEquals(0, path.getNextNodeIndex());
        path.advance();
        assertEquals(1, path.getNextNodeIndex());
        assertNull(path.getNextTransition());
    }

    @Test
    void independentlyConstructedPathsDoNotShareTransitionMetadata() {
        Node first = node(0, 64, 0);
        Node second = node(1, 64, 0);
        VasyanEdge firstEdge = edge(first, second, MoveType.DIG, 4, new BlockPos(1, 64, 0),
            new BlockPos(1, 65, 0), null);
        VasyanEdge secondEdge = edge(first, second, MoveType.PLACE, 3, null, null,
            new BlockPos(1, 63, 0));

        VasyanPath firstPath = new VasyanPath(List.of(first, second), List.of(firstEdge), second.asBlockPos(), true);
        VasyanPath secondPath = new VasyanPath(List.of(first, second), List.of(secondEdge), second.asBlockPos(), true);

        assertNotSame(firstPath.getNextTransition(), secondPath.getNextTransition());
        assertEquals(MoveType.DIG, firstPath.getNextTransition().moveType());
        assertEquals(MoveType.PLACE, secondPath.getNextTransition().moveType());
    }

    @Test
    void inheritedPathOperationsAreRejectedToPreserveTransitions() {
        Node first = node(0, 64, 0);
        Node second = node(1, 64, 0);
        VasyanPath path = new VasyanPath(List.of(first, second),
            List.of(edge(first, second, MoveType.WALK, 1, null, null, null)), second.asBlockPos(), true);

        assertThrows(UnsupportedOperationException.class, () -> path.replaceNode(0, node(5, 64, 0)));
        assertThrows(UnsupportedOperationException.class, () -> path.truncateNodes(1));
    }

    @Test
    void pathRequiresOneTransitionForEveryNodeStep() {
        Node first = node(0, 64, 0);
        Node second = node(1, 64, 0);

        assertThrows(IllegalArgumentException.class,
            () -> new VasyanPath(List.of(first, second), List.of(), second.asBlockPos(), true));
        assertThrows(IllegalArgumentException.class,
            () -> new VasyanPath(List.of(first), List.of(edge(first, first, MoveType.WALK, 1, null, null, null)),
                first.asBlockPos(), true));
    }

    @Test
    void mutationPositionsAreSnapshotAtEdgeConstruction() {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 64, 0);
        VasyanEdge edge = new VasyanEdge(node(0, 64, 0), node(1, 64, 0), MoveType.DIG, 1,
            mutable, null, null);

        mutable.set(new Vec3i(9, 9, 9));

        assertEquals(new BlockPos(1, 64, 0), edge.digFoot());
    }

    private static Node node(int x, int y, int z) {
        return new Node(x, y, z);
    }

    private static VasyanEdge edge(Node from, Node to, MoveType type, float cost,
                                   BlockPos digFoot, BlockPos digHead, BlockPos placePosition) {
        return new VasyanEdge(from, to, type, cost, digFoot, digHead, placePosition);
    }
}
