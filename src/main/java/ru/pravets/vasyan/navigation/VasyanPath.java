package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.List;

/** A vanilla-compatible path with transition metadata for each movement step. */
public final class VasyanPath extends Path {

    private final List<VasyanEdge> transitions;

    public VasyanPath(List<Node> nodes, List<VasyanEdge> transitions, BlockPos target, boolean reachesTarget) {
        super(List.copyOf(nodes), target, reachesTarget);
        if (transitions.size() > nodes.size()) {
            throw new IllegalArgumentException("A path cannot have more transitions than nodes");
        }
        this.transitions = List.copyOf(transitions);
    }

    /** Returns the transition for the next node, or null when the path is exhausted. */
    public VasyanEdge getNextTransition() {
        int index = getNextNodeIndex();
        return index < transitions.size() ? transitions.get(index) : null;
    }

    public List<VasyanEdge> transitions() { return transitions; }
}
