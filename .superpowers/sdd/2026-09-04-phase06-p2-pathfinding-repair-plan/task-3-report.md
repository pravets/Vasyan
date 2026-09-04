# Task 3 Report: Edge-Aware Path Search

## Status

Implemented and committed as requested.

## Implementation

- Replaced `VasyanPathFinder`'s vanilla wrapper behavior with an A* search.
- Search states retain their incoming `VasyanEdge`, including transition type and mutation positions.
- Search consumes `VasyanNodeEvaluator.getEdges(...)` directly, so WALK, DIG, PLACE, and PILLAR_UP candidates remain distinct even when coordinates match.
- Edge costs are held in search state; `Node.costMalus` is not modified.
- Preserved target proximity semantics, maximum distance, reach range, evaluator lifecycle, and the vanilla-style `maxVisitedNodes * accuracy` search budget.
- No entity wiring or navigation executor changes were made.

## Tests

- Focused test: `./gradlew test --tests ru.pravets.vasyan.navigation.VasyanPathFinderTest`
- Navigation tests: `./gradlew test --tests 'ru.pravets.vasyan.navigation.*'`
- Compilation: `./gradlew compileJava compileTestJava`
- Full suite: `./gradlew test`

All commands completed successfully. Gradle emitted only its existing deprecation and JVM sharing warnings.

## Commit

`feat(navigation): implement edge-aware VasyanPathFinder`

Author: `Iosif Pravets <i@pravets.ru>`

## Concerns

- The implementation intentionally keeps the existing vanilla method signature and returns `null` when no route is found or the budget is exhausted.
- The pathfinder is not wired to `VasyanEntity` and does not execute transitions, as required by Task 3.

## Review Fixes

- Removed the inadmissible `1.5` heuristic multiplier; the current heuristic is zero, so nonnegative edge costs preserve cost-optimal first-goal behavior.
- Search-state keys now include destination coordinates and incoming edge signature: move type plus DIG/PLACE mutation positions. This prevents coordinate-only deduplication from discarding distinct transition metadata.
- Added regressions for cheaper longer WALK routes, coordinate-equal metadata alternatives, and continued cheaper WALK selection.
