package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.List;

/** A vanilla-compatible path with transition metadata for each movement step. */
public final class VasyanPath extends Path {

    private final List<VasyanEdge> transitions;

    /** Creates a path with one transition per adjacent node pair and immutable owned inputs. */
    public VasyanPath(List<Node> nodes, List<VasyanEdge> transitions, BlockPos target, boolean reachesTarget) {
        super(new ArrayList<>(nodes), target, reachesTarget);
        int expectedTransitions = Math.max(0, nodes.size() - 1);
        if (transitions.size() != expectedTransitions) {
            throw new IllegalArgumentException("A path must have one transition per node step");
        }
        this.transitions = List.copyOf(transitions);
    }

    /** Returns the transition for the next node, or null when the path is exhausted. */
    public VasyanEdge getNextTransition() {
        int index = getNextNodeIndex();
        return index < transitions.size() ? transitions.get(index) : null;
    }

    /** Returns the immutable list of exactly node count minus one transitions. */
    public List<VasyanEdge> transitions() { return transitions; }

    /** Vanilla mutation is prohibited because it cannot update edge metadata atomically. */
    @Override
    public void truncateNodes(int length) {
        throw new UnsupportedOperationException("VasyanPath transitions are immutable");
    }

    /** Vanilla mutation is prohibited because it cannot update edge endpoints atomically. */
    @Override
    public void replaceNode(int index, Node node) {
        throw new UnsupportedOperationException("VasyanPath transitions are immutable");
    }
}
