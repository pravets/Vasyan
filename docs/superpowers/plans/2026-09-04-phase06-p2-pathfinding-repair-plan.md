# Phase 0.6 P2 Pathfinding Repair Plan

**Goal:** Replace coordinate-level `Node -> MoveType` metadata with transition-level metadata so DIG, PLACE, and PILLAR-UP are planned and executed correctly.

## Root Causes

1. `VasyanNodeEvaluator` stores one `MoveType` per coordinate. The same coordinate can be reached by WALK or a mutation edge, so the action is ambiguous.
2. Special-edge cost is added directly to shared `Node.costMalus`, allowing costs from different incoming edges to accumulate based on expansion order.
3. Tests invoke `addSpecialEdges` directly and no custom navigation is wired into `VasyanEntity`; gameplay execution is not implemented.

## Repair Strategy

1. Introduce `VasyanEdge` carrying `from`, `to`, `MoveType`, cost, and mutation metadata.
2. Introduce `VasyanPath` carrying nodes plus one transition record per path step.
3. Replace the thin `VasyanPathFinder` wrapper with edge-aware A* that never mutates shared `Node.costMalus` for special edges.
4. Keep `DigRules`, `ScaffoldBlocks`, config, and P1 recovery behavior; reuse them from the evaluator and executor.
5. Add `VasyanPathNavigation` that executes exactly one mutation per tick and replans when the world invalidates upcoming transitions.
6. Wire the custom navigation only after path metadata and executor tests pass.

## Required Tests

- A coordinate reachable by WALK and DIG preserves the selected transition type.
- Different incoming edges do not accumulate shared `Node.costMalus`.
- Mixed paths preserve `WALK -> DIG -> PLACE -> PILLAR_UP` transitions.
- Replanning clears old transition metadata.
- DIG clears foot/head and leaves drops; PLACE consumes one permitted scaffold item; PILLAR_UP does not consume an item on failed placement.
- RCON scenario K traverses wall, deep gap, and short cliff without P1 recovery branches.
- Existing scenarios A-J and full Gradle tests remain green.

## Constraints

- Do not reset or rewrite existing history destructively.
- Do not wire the current coordinate-level `MoveType` implementation into `VasyanEntity`.
- Use official Mojang mappings for Minecraft 1.20.1 / Forge 47.2.0.
- One mutation per server tick; all world mutation is server-side.
- Preserve `PathMonitor` and the P1 recovery ladder as fallback safety behavior.
