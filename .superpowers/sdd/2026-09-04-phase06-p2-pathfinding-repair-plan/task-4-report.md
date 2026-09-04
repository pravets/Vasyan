# Task 4 Report

## Status

Implemented `VasyanPathNavigation` without wiring it into `VasyanEntity`.

## Implementation

- Extends `AmphibiousPathNavigation`.
- Overrides the official-mapping `createPathFinder(int)` lifecycle hook with `VasyanPathFinder`.
- Executes at most one current `VasyanEdge` mutation per server tick before delegating movement to vanilla `followThePath`.
- DIG validates the current world and destroys exact edge foot/head positions with drops enabled.
- PLACE validates air, adjacency, whitelist membership, and full-cube scaffold shape; it consumes one item only after successful placement.
- PILLAR_UP places at the edge placement position and requests a jump only after placement succeeds.
- Adds periodic lookahead validation across the next five transitions and calls `recomputePath()` when a transition is invalidated.
- Leaves `PathMonitor` unchanged.

## API Verification

`javap -p` was run against the Forge 1.20.1 official-mapped jar. It confirmed:

- `AmphibiousPathNavigation.createPathFinder(int)` is protected and overridable.
- `PathNavigation.tick()` invokes the protected `followThePath()` lifecycle method.
- `PathNavigation.path`, `mob`, and `level` are protected fields.
- `Path` exposes `getNextNodeIndex()`, `getNextNode()`, and `advance()`.

## Tests

- Focused navigation tests: passed.
- `./gradlew compileJava compileTestJava`: passed.
- `./gradlew test`: passed.

The test seam uses the real edge-dispatch executor and covers walk delegation and exhausted-path idempotence. Full server-world mutation coverage remains a concern for a follow-up because the requested no-entity-wiring scope leaves no concrete production construction path for a world-backed `VasyanEntity` in this test fixture.

## Commit

Commit: `feat(navigation): add edge-aware path executor`
