package ru.pravets.vasyan.navigation;

import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Thin {@link PathFinder} subtype that keeps the {@link VasyanNodeEvaluator}
 * reachable so the navigation layer can read per-edge {@link MoveType}s back
 * after a path is computed. Vanilla {@link PathFinder} stores the evaluator
 * in a private field, hence the separate reference here.
 *
 * @author Iosif Pravets &lt;i@pravets.ru&gt;
 */
public class VasyanPathFinder extends PathFinder {

    private final VasyanNodeEvaluator vasyanEvaluator;

    public VasyanPathFinder(VasyanNodeEvaluator evaluator, int maxVisitedNodes) {
        super(evaluator, maxVisitedNodes);
        this.vasyanEvaluator = evaluator;
    }

    /** The evaluator this finder plans with; also usable for {@code getMoveType} lookups. */
    public VasyanNodeEvaluator vasyanEvaluator() {
        return this.vasyanEvaluator;
    }
}
