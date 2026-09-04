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

## Tasks

### Task 1: Introduce transition metadata and edge tests

Create `VasyanEdge` and `VasyanPath` in `navigation/`. Define immutable transition metadata, including mutation positions needed by execution. Add tests for mixed transitions, duplicate coordinates with different edge types, and metadata reset between paths. Do not wire the entity yet.

### Task 2: Replace coordinate metadata in the evaluator

Refactor `VasyanNodeEvaluator` to produce transition candidates without storing `MoveType` by node hash and without mutating shared `Node.costMalus`. Preserve vanilla neighbor behavior and all current DIG/PLACE/PILLAR safety predicates. Extend tests for multiple incoming edges, selected edge type, costs, and special-edge deduplication.

### Task 3: Implement edge-aware path search

Replace `VasyanPathFinder`'s wrapper behavior with A* that carries the incoming `VasyanEdge` in search state and reconstructs a `VasyanPath`. Preserve search budget and target semantics. Add a real path-search test proving the returned transition sequence is correct.

### Task 4: Implement custom navigation executor

Create `VasyanPathNavigation` extending `AmphibiousPathNavigation`. Execute one DIG, PLACE, or PILLAR-UP mutation per tick using current-world validation, whitelist filtering, idempotent checks, and server-side guards. Add focused executor tests for drops, inventory consumption, failed placement, and duplicate-tick behavior. Implement nearest-transition auto-replan.

### Task 5: Wire navigation and verify regressions

Change `VasyanEntity.createNavigation(Level)` to return `VasyanPathNavigation`. Run focused entity tests, full Gradle tests, and existing behavior scenarios A-J. Fix only integration defects caused by this repair.

### Task 6: Add scenario K and update docs

Add the RCON obstacle-course scenario K (wall, deep gap, short cliff), assert arrival and new executor logs without legacy recovery branches. Update `docs/CAPABILITIES.md` and `ROADMAP.md` after behavior verification.
