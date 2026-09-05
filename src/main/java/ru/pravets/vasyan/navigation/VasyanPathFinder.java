package ru.pravets.vasyan.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** A* path finder that keeps the edge which produced every search state. */
public class VasyanPathFinder extends net.minecraft.world.level.pathfinder.PathFinder {
    private final VasyanNodeEvaluator vasyanEvaluator;
    private final int maxVisitedNodes;

    public VasyanPathFinder(VasyanNodeEvaluator evaluator, int maxVisitedNodes) {
        super(evaluator, maxVisitedNodes);
        this.vasyanEvaluator = evaluator;
        this.maxVisitedNodes = maxVisitedNodes;
    }

    public VasyanNodeEvaluator vasyanEvaluator() { return vasyanEvaluator; }

    @Override
    public Path findPath(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float maxDistance,
                         int reachRange, float accuracy) {
        vasyanEvaluator.prepare(region, mob);
        try {
            Node start = vasyanEvaluator.getStart();
            if (start == null || targets.isEmpty()) return null;
            int limit = (int) (maxVisitedNodes * accuracy);
            if (limit <= 0) return null;
            List<BlockPos> targetList = List.copyOf(targets);
            vasyanEvaluator.setNavigationTarget(targetList.stream()
                .min(Comparator.comparingDouble(start.asBlockPos()::distSqr))
                .orElse(null));
            SearchState initial = new SearchState(start, null, null, 0,
                heuristic(start, targetList));
            PriorityQueue<SearchState> open = new PriorityQueue<>(Comparator.comparingDouble(s -> s.f));
            Map<SearchKey, SearchState> best = new HashMap<>();
            Set<SearchKey> closed = new HashSet<>();
            open.add(initial);
            best.put(initial.key, initial);
            SearchState reached = null;
            int visited = 0;
            while (!open.isEmpty() && visited++ < limit) {
                SearchState state = open.poll();
                if (best.get(state.key) != state || !closed.add(state.key)) continue;
                if (targetList.stream().anyMatch(target -> state.node.distanceManhattan(target) <= reachRange)) {
                    reached = state;
                    break;
                }
                if (state.node.distanceTo(start) >= maxDistance) continue;
                for (VasyanEdge edge : vasyanEvaluator.getEdges(region, state.node)) {
                    Node next = edge.to();
                    float cost = state.g + edge.cost();
                    SearchKey key = SearchKey.of(next, edge);
                    SearchState old = best.get(key);
                    if (closed.contains(key) || (old != null && cost >= old.g)) continue;
                    SearchState candidate = new SearchState(next, state, edge, cost,
                        cost + heuristic(next, targetList));
                    best.put(key, candidate);
                    open.add(candidate);
                }
            }
            return reached == null ? null : reconstruct(reached, targetList);
        } finally {
            vasyanEvaluator.setNavigationTarget(null);
            vasyanEvaluator.done();
        }
    }

    private static float heuristic(Node node, List<BlockPos> targets) {
        return 0;
    }

    private static VasyanPath reconstruct(SearchState end, List<BlockPos> targets) {
        List<Node> nodes = new ArrayList<>();
        List<VasyanEdge> edges = new ArrayList<>();
        for (SearchState state = end; state != null; state = state.parent) {
            nodes.add(0, state.node);
            if (state.incoming != null) edges.add(0, state.incoming);
        }
        BlockPos target = targets.stream().min(Comparator.comparingDouble(end.node::distanceTo)).orElse(end.node.asBlockPos());
        return new VasyanPath(nodes, edges, target, true);
    }

    private record SearchState(Node node, SearchState parent, VasyanEdge incoming, float g, float f, SearchKey key) {
        SearchState(Node node, SearchState parent, VasyanEdge incoming, float g, float f) {
            this(node, parent, incoming, g, f, SearchKey.of(node, incoming));
        }
    }

    private record SearchKey(int x, int y, int z, MoveType moveType, BlockPos digFoot,
                             BlockPos digHead, BlockPos placePosition) {
        static SearchKey of(Node node, VasyanEdge edge) {
            return edge == null ? new SearchKey(node.x, node.y, node.z, null, null, null, null)
                : new SearchKey(node.x, node.y, node.z, edge.moveType(), edge.digFoot(), edge.digHead(), edge.placePosition());
        }
    }
}
