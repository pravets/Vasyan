Status: complete

Commit: 34612bffe7419b49ac71cb4b6f2320414c2b18fe

Tests:
- `./gradlew.bat test --tests ru.pravets.vasyan.navigation.VasyanEdgePathTest` passed.
- `./gradlew.bat compileJava compileTestJava` passed.
- `./gradlew.bat test` passed.
- Official Minecraft 1.20.1 `Path` constructor and `getNextNodeIndex()` were verified with `javap`.

Concerns:
- `VasyanNodeEvaluator` still stores coordinate-level `MoveType` metadata by design; evaluator refactoring is deferred to Task 2.
- `VasyanEdge` and `VasyanPath` are not wired into pathfinding or entity navigation; wiring is deferred to later repair tasks.

Report path: `.superpowers/sdd/2026-09-04-phase06-p2-pathfinding-repair-plan/task-1-report.md`

Task 1 Review Fixes:
- `VasyanPath` now gives vanilla `Path` a defensive mutable `ArrayList`, preserving `truncateNodes` and `replaceNode` compatibility.
- `VasyanPath` requires exactly `max(0, nodes.size() - 1)` transitions.
- `VasyanEdge` snapshots every non-null mutation position with `BlockPos.immutable()`.
- Added duplicate-coordinate path entries with distinct indexed WALK and DIG transitions, plus regressions for the other review findings.

Fix verification:
- `./gradlew.bat test --tests ru.pravets.vasyan.navigation.VasyanEdgePathTest` passed.
- `./gradlew.bat compileJava compileTestJava` passed.
- `./gradlew.bat test` passed.
