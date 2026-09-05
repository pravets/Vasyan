# Phase 0.6 P2 — Dig/Place Pathfinding Implementation Plan (superseded)

> Historical design record. The implementation and review repairs are tracked in
> [the Phase 0.6 P2 pathfinding repair plan](2026-09-04-phase06-p2-pathfinding-repair-plan.md).
> The shipped architecture uses edge-aware `VasyanEdge` metadata and `VasyanPath`; it does
> not model transitions as `Node -> MoveType` or rely on a thin `PathFinder` wrapper.

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make DIG / PLACE / PILLAR-UP first-class edges of the bot's path graph by introducing a custom `VasyanNodeEvaluator`, `VasyanPathFinder`, and `VasyanPathNavigation` that execute these edges while preserving the P1 recovery ladder.

**Architecture:** A custom `VasyanPathNavigation` replaces `AmphibiousPathNavigation` for all routes. It uses `VasyanPathFinder` → `VasyanNodeEvaluator`, which adds DIG/PLACE/PILLAR-UP neighbors on top of vanilla walking. `VasyanPathNavigation.followPath()` pauses movement and performs the world mutation when the current path node is a DIG/PLACE/PILLAR node. Pure logic (breakability rules, scaffold scoring, cost calculation) is extracted into small, unit-testable utilities; the P1 `PathMonitor` recovery ladder remains as a safety net.

**Tech Stack:** Java 17, Minecraft 1.20.1 / Forge 47.2.0, JUnit 5, Gradle 8.5, McTestBootstrap for Minecraft-in-a-thread tests, RCON behavior tests via `scripts/behavior/behavior_test.py`.

**Spec:** `docs/superpowers/specs/2026-08-31-phase06-p2-dig-place-pathfinding-design.md`

## Global Constraints

- Java 17, 4-space indentation, max line length 120.
- PascalCase classes, camelCase methods/variables, UPPER_SNAKE_CASE constants.
- JavaDoc for public APIs.
- Author commits as `Iosif Pravets <i@pravets.ru>`.
- One PR = one task (this whole plan is one PR).
- Local build: `nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`.
- Full CI build + behavior tests must pass.
- Do not break existing P1 behavior scenarios A–J.
- Real tools not required; dig cost uses block hardness only.

---

### Task 1: Extract reusable dig and scaffold utilities

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/navigation/DigRules.java`
- Create: `src/main/java/ru/pravets/vasyan/navigation/ScaffoldBlocks.java`
- Modify: `src/main/java/ru/pravets/vasyan/navigation/VasyanPathing.java`
- Test: `src/test/java/ru/pravets/vasyan/navigation/DigRulesTest.java`
- Test: `src/test/java/ru/pravets/vasyan/navigation/ScaffoldBlocksTest.java`

**Interfaces:**
- Consumes: existing `VasyanEntity.getInventory()` via `VasyanInventory`.
- Produces:
  - `DigRules.isBreakable(Level, BlockPos, boolean includeOres)` — reusable breakability predicate.
  - `DigRules.isFallingBlock(Level, BlockPos)` — true for sand/gravel/anvil above.
  - `DigRules.wouldCreateFlow(Level, BlockPos)` — true if any adjacent cell is a flowing/source liquid.
  - `DigRules.NEVER_BREAK` and `DigRules.UNBREAKABLE` moved from `VasyanPathing`.
  - `ScaffoldBlocks.findBestStack(VasyanInventory, Level, BlockPos)` — returns `ItemStack` or `null`.
  - `ScaffoldBlocks.score(BlockState, Level, BlockPos)` — integer score (lower = better).

- [ ] **Step 1: Write `DigRules` with the predicates.**

Move `UNBREAKABLE`, `NEVER_BREAK`, and `isBreakable` from `VasyanPathing` into `DigRules`. Add `wouldCreateFlow` using `FluidState.isSource()` / `FluidState.is(FluidTags.WATER)` etc. Add `isFallingBlock` by checking the block state above the given position recursively for `Blocks.SAND`, `Blocks.GRAVEL`, `Blocks.ANVIL`, `Blocks.DAMAGED_ANVIL`, `Blocks.CHIPPED_ANVIL`.

```java
public final class DigRules {
    public static final Set<Block> UNBREAKABLE = Set.of(...);
    public static final Set<Block> NEVER_BREAK = Set.of(...);

    public static boolean isBreakable(Level level, BlockPos pos, boolean includeOres) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || isLiquid(state.getFluidState()) || state.canBeReplaced()) return false;
        if (!includeOres && NEVER_BREAK.contains(state.getBlock())) return false;
        return !UNBREAKABLE.contains(state.getBlock()) && state.getDestroySpeed(level, pos) >= 0.0F;
    }

    public static boolean wouldCreateFlow(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            FluidState fluid = level.getBlockState(pos.relative(dir)).getFluidState();
            if (!fluid.isEmpty() && (fluid.isSource() || fluid.is(FluidTags.WATER) || fluid.is(FluidTags.LAVA))) return true;
        }
        return false;
    }

    public static boolean isFallingBlock(Level level, BlockPos pos) {
        BlockPos above = pos.above();
        while (!level.isOutsideBuildHeight(above)) {
            Block b = level.getBlockState(above).getBlock();
            if (b == Blocks.SAND || b == Blocks.GRAVEL || b instanceof AnvilBlock) return true;
            if (!level.getBlockState(above).isAir()) break;
            above = above.above();
        }
        return false;
    }
}
```

- [ ] **Step 2: Write `ScaffoldBlocks` with scoring.**

Move `findScaffoldStack` / `scaffoldScore` from `VasyanPathing` here. Use the same preference order (dirt/sand/gravel → cobble/stone → planks/logs). Keep `hasAdjacentSolid` in `VasyanPathing` (it is placement glue, not selection).

- [ ] **Step 3: Update `VasyanPathing` to delegate to the new utilities.**

Replace the duplicated logic with calls to `DigRules` and `ScaffoldBlocks`. Ensure behavior is byte-for-byte identical: existing tests and P1 scenarios A–J must still pass.

- [ ] **Step 4: Write unit tests for `DigRules` and `ScaffoldBlocks`.**

Test: `DigRulesTest` uses a `McTestBootstrap` world to assert that ore/bedrock are not breakable, that dirt is breakable, that digging next to water returns `wouldCreateFlow == true`, and that sand above makes `isFallingBlock` true.

Test: `ScaffoldBlocksTest` asserts the scoring order with `Blocks.DIRT`, `Blocks.COBBLESTONE`, `Blocks.OAK_PLANKS`, and that `findBestStack` returns null when inventory is empty.

- [ ] **Step 5: Run tests.**

```bash
./gradlew test --tests "ru.pravets.vasyan.navigation.DigRulesTest" \
  --tests "ru.pravets.vasyan.navigation.ScaffoldBlocksTest"
```
Expected: all tests pass.

- [ ] **Step 6: Commit.**

```bash
git add src/main/java/ru/pravets/vasyan/navigation/DigRules.java \
  src/main/java/ru/pravets/vasyan/navigation/ScaffoldBlocks.java \
  src/main/java/ru/pravets/vasyan/navigation/VasyanPathing.java \
  src/test/java/ru/pravets/vasyan/navigation/DigRulesTest.java \
  src/test/java/ru/pravets/vasyan/navigation/ScaffoldBlocksTest.java
git commit --author="Iosif Pravets <i@pravets.ru>" -m "refactor(navigation): extract DigRules and ScaffoldBlocks utilities"
```

---

### Task 2: Add new navigation configuration keys

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/config/VasyanConfig.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: static config fields used by Tasks 3–7.
  - `ForgeConfigSpec.IntValue NAV_DIG_COST`
  - `ForgeConfigSpec.IntValue NAV_PLACE_COST`
  - `ForgeConfigSpec.IntValue NAV_LIQUID_COST`
  - `ForgeConfigSpec.IntValue NAV_ENTITY_COST`
  - `ForgeConfigSpec.IntValue NAV_MAX_DROP_DOWN`
  - `ForgeConfigSpec.DoubleValue NAV_DIG_HARDNESS_FACTOR`
  - `ForgeConfigSpec.ConfigValue<List<String>> NAV_SCAFFOLD_WHITELIST`
  - `ForgeConfigSpec.IntValue NAV_REPLAN_CHECK_INTERVAL_TICKS`

- [ ] **Step 1: Add fields and builder calls in the `[navigation]` section.**

Place them after existing NAV_* keys. Example builder call:

```java
NAV_DIG_COST = builder
    .comment("Base A* cost added by a DIG edge.")
    .defineInRange("digCost", 4, 0, 1000);
```

Use `defineList` for whitelist with default `List.of("minecraft:dirt", "minecraft:cobblestone")`. Use existing naming conventions (uppercase static fields).

- [ ] **Step 2: Compile only.**

```bash
./gradlew compileJava
```
Expected: compiles.

- [ ] **Step 3: Commit.**

```bash
git add src/main/java/ru/pravets/vasyan/config/VasyanConfig.java
git commit --author="Iosif Pravets <i@pravets.ru>" -m "config(navigation): add dig/place pathfinding costs"
```

---

### Task 3: Introduce MoveType enum

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/navigation/MoveType.java`
- Create: `src/main/java/ru/pravets/vasyan/navigation/DigPlaceCosts.java`
- Test: `src/test/java/ru/pravets/vasyan/navigation/DigPlaceCostsTest.java`

**Interfaces:**
- Consumes: `VasyanConfig` new keys (Task 2), `DigRules` (Task 1).
- Produces:
  - `enum MoveType { WALK, DIG, PLACE, PILLAR_UP }`
  - `DigPlaceCosts.digCost(Level, BlockPos)` — returns total A* cost for a DIG edge.
  - `DigPlaceCosts.placeCost()` / `DigPlaceCosts.pillarUpCost()`.

- [ ] **Step 1: Write `MoveType`.**

```java
public enum MoveType {
    WALK, DIG, PLACE, PILLAR_UP
}
```

- [ ] **Step 2: Write `DigPlaceCosts`.**

```java
public final class DigPlaceCosts {
    public static int walkCost() { return 1; }

    public static int digCost(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) hardness = 0;
        double factor = VasyanConfig.NAV_DIG_HARDNESS_FACTOR.get();
        return VasyanConfig.NAV_DIG_COST.get() + (int) Math.round(hardness * factor);
    }

    public static int placeCost() {
        return VasyanConfig.NAV_PLACE_COST.get();
    }

    public static int pillarUpCost() {
        return VasyanConfig.NAV_PLACE_COST.get() + walkCost();
    }
}
```

- [ ] **Step 3: Write unit tests.**

`DigPlaceCostsTest` asserts that dirt costs less than stone, that `placeCost()` equals `NAV_PLACE_COST`, and that `pillarUpCost()` equals place + walk.

- [ ] **Step 4: Run tests.**

```bash
./gradlew test --tests "ru.pravets.vasyan.navigation.DigPlaceCostsTest"
```
Expected: pass.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/ru/pravets/vasyan/navigation/MoveType.java \
  src/main/java/ru/pravets/vasyan/navigation/DigPlaceCosts.java \
  src/test/java/ru/pravets/vasyan/navigation/DigPlaceCostsTest.java
git commit --author="Iosif Pravets <i@pravets.ru>" -m "feat(navigation): add MoveType and DigPlaceCosts calculator"
```

---

### Task 4: Implement `VasyanNodeEvaluator`

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/navigation/VasyanNodeEvaluator.java`
- Test: `src/test/java/ru/pravets/vasyan/navigation/VasyanNodeEvaluatorTest.java`

**Interfaces:**
- Consumes: `DigRules`, `ScaffoldBlocks`, `DigPlaceCosts`, `MoveType`.
- Produces:
  - `VasyanNodeEvaluator` constructor takes `Mob mob, Level level`.
  - `getMoveType(PathPoint node) → MoveType` — later read by navigation.

- [ ] **Step 1: Create subclass of `WalkNodeEvaluator`.**

Override the vanilla neighbor-generation method (match the exact parent signature while coding; in MCP 1.20.1 the method adds neighbors to an internal list). The logic:

1. Let the parent add vanilla walking neighbors first.
2. For each horizontal direction from the current node:
   - If the foot cell and head cell in that direction are breakable by `DigRules.isBreakable(..., false)` and safe (`!wouldCreateFlow` and `!isFallingBlock`), add a DIG neighbor at the position beyond the obstacle with cost `DigPlaceCosts.digCost(level, obstaclePos)`.
   - If the foot cell is air/liquid and the cell below is also air/liquid for more than `maxDropDown` levels, and `ScaffoldBlocks.findBestStack` would return a stack, add a PLACE neighbor on the near side of the gap with cost `DigPlaceCosts.placeCost()`.
3. For vertical up:
   - If the cell directly above is open for 2+ blocks and a scaffold block is available, add a PILLAR_UP neighbor one block up with cost `DigPlaceCosts.pillarUpCost()`.

Store the `MoveType` mapping per `PathPoint` (e.g., `Int2ObjectMap<MoveType>` keyed by `PathPoint.nodeHash` or node index). Expose `getMoveType(PathPoint)`.

- [ ] **Step 2: Write McTestBootstrap tests.**

`VasyanNodeEvaluatorTest` sets up three small worlds:
- A 2-block-high dirt wall in front of the bot. Assert that the evaluator returns a DIG neighbor beyond the wall.
- A 2-block-wide pit deeper than `maxDropDown`. Assert a PLACE neighbor is generated.
- A 2-block-high vertical cliff. Assert a PILLAR_UP neighbor is generated.

Use the existing `McTestBootstrap` pattern (see `src/test/java/ru/pravets/vasyan/test/McTestBootstrap.java` and `VerticalTraversalPlannerTest`).

- [ ] **Step 3: Run tests.**

```bash
./gradlew test --tests "ru.pravets.vasyan.navigation.VasyanNodeEvaluatorTest"
```
Expected: pass.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/ru/pravets/vasyan/navigation/VasyanNodeEvaluator.java \
  src/test/java/ru/pravets/vasyan/navigation/VasyanNodeEvaluatorTest.java
git commit --author="Iosif Pravets <i@pravets.ru>" -m "feat(navigation): add VasyanNodeEvaluator with DIG/PLACE/PILLAR_UP edges"
```

---

### Task 5: Implement `VasyanPathFinder`

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/navigation/VasyanPathFinder.java`

**Interfaces:**
- Consumes: `VasyanNodeEvaluator`, `PathBudgets` (existing P1).
- Produces: `VasyanPathFinder` used by `VasyanPathNavigation`.

- [ ] **Step 1: Subclass `PathFinder`.**

```java
public class VasyanPathFinder extends PathFinder {
    public VasyanPathFinder(VasyanNodeEvaluator evaluator, int maxVisitedNodes) {
        super(evaluator, maxVisitedNodes);
    }

    public VasyanNodeEvaluator vasyanEvaluator() {
        return (VasyanNodeEvaluator) this.nodeEvaluator;
    }
}
```

The field `nodeEvaluator` may be private in `PathFinder`; if so, keep a separate reference in `VasyanPathFinder`.

- [ ] **Step 2: Compile only.**

```bash
./gradlew compileJava
```
Expected: pass.

- [ ] **Step 3: Commit.**

```bash
git add src/main/java/ru/pravets/vasyan/navigation/VasyanPathFinder.java
git commit --author="Iosif Pravets <i@pravets.ru>" -m "feat(navigation): add VasyanPathFinder"
```

---

### Task 6: Implement `VasyanPathNavigation`

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/navigation/VasyanPathNavigation.java`
- Test: `src/test/java/ru/pravets/vasyan/navigation/VasyanPathNavigationTest.java`

**Interfaces:**
- Consumes: `VasyanPathFinder`, `MoveType`, `ScaffoldBlocks`, `DigRules`, `PathBudgets`.
- Produces: `VasyanPathNavigation` that executes DIG/PLACE/PILLAR nodes.

- [ ] **Step 1: Subclass `AmphibiousPathNavigation`.**

```java
public class VasyanPathNavigation extends AmphibiousPathNavigation {
    private int replanCheckCooldown = 0;

    public VasyanPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        return new VasyanPathFinder(
            new VasyanNodeEvaluator((VasyanEntity) this.mob, this.level),
            maxVisitedNodes);
    }
}
```

Note: cast `this.mob` to `VasyanEntity` if the entity is always a Vasyan bot; otherwise pass generic `Mob` and cast only where needed for inventory access.

- [ ] **Step 2: Override `followPath()` to handle special nodes.**

Pattern:

```java
@Override
protected void followPath() {
    Path path = this.getPath();
    if (path == null || path.isDone()) {
        super.followPath();
        return;
    }
    VasyanPathFinder finder = (VasyanPathFinder) this.pathFinder;
    MoveType type = finder.vasyanEvaluator().getMoveType(path.getNextNode());
    if (type == MoveType.WALK || type == null) {
        super.followPath();
        return;
    }
    this.stop(); // pause movement for one tick
    VasyanEntity vasyan = (VasyanEntity) this.mob;
    switch (type) {
        case DIG -> executeDig(vasyan, path.getNextNodePos());
        case PLACE -> executePlace(vasyan, path.getNextNodePos());
        case PILLAR_UP -> executePillarUp(vasyan);
    }
}
```

`executeDig`: `level.destroyBlock(pos, true)`, `vasyan.swing(InteractionHand.MAIN_HAND, true)`. The node should now be passable on the next tick.

`executePlace`: pick the best scaffold stack via `ScaffoldBlocks.findBestStack`, place at the target node, shrink the stack.

`executePillarUp`: place a scaffold block under the bot's feet and trigger a jump/climb so the bot ends up one block higher.

- [ ] **Step 3: Add auto-replan check in `tick()`.**

```java
@Override
public void tick() {
    if (--replanCheckCooldown <= 0 && this.getPath() != null) {
        replanCheckCooldown = VasyanConfig.NAV_REPLAN_CHECK_INTERVAL_TICKS.get();
        if (pathNeedsReplan()) {
            this.recomputePath();
        }
    }
    super.tick();
}
```

`pathNeedsReplan()` checks the next ~5 nodes: a WALK node that is now blocked, a PLACE node that has been filled by something else, or a PILLAR_UP node whose pillar was removed.

- [ ] **Step 4: Write McTestBootstrap tests.**

`VasyanPathNavigationTest`: spawn a bot, set a target behind a dirt wall, and assert the bot reaches the target (position within 1 block). Repeat for a pit with a bridge and a 2-block-high cliff.

- [ ] **Step 5: Run tests.**

```bash
./gradlew test --tests "ru.pravets.vasyan.navigation.VasyanPathNavigationTest"
```
Expected: pass.

- [ ] **Step 6: Commit.**

```bash
git add src/main/java/ru/pravets/vasyan/navigation/VasyanPathNavigation.java \
  src/test/java/ru/pravets/vasyan/navigation/VasyanPathNavigationTest.java
git commit --author="Iosif Pravets <i@pravets.ru>" -m "feat(navigation): add VasyanPathNavigation that executes DIG/PLACE/PILLAR nodes"
```

---

### Task 7: Wire `VasyanPathNavigation` into `VasyanEntity`

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/entity/VasyanEntity.java`
- Test: existing `PathfindActionTest`, `GatherResourceActionTest`, and behavior scenarios A–J.

**Interfaces:**
- Consumes: `VasyanPathNavigation` (Task 6).
- Produces: all bots now use the new navigation.

- [ ] **Step 1: Replace the navigation factory.**

In `VasyanEntity.createNavigation(Level level)`:

```java
@Override
protected PathNavigation createNavigation(Level level) {
    return new VasyanPathNavigation(this, level);
}
```

- [ ] **Step 2: Run unit tests.**

```bash
./gradlew test --tests "ru.pravets.vasyan.action.actions.PathfindActionTest" \
  --tests "ru.pravets.vasyan.action.actions.GatherResourceActionTest"
```
Expected: pass.

- [ ] **Step 3: Run behavior scenarios A–J.**

```bash
./gradlew jarJar
python scripts/behavior/behavior_test.py
```
Expected: all scenarios A–J pass.

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/ru/pravets/vasyan/entity/VasyanEntity.java
git commit --author="Iosif Pravets <i@pravets.ru>" -m "feat(entity): use VasyanPathNavigation for all bots"
```

---

### Task 8: Verify P1 recovery ladder still works and adjust thresholds

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/navigation/VasyanPathing.java`
- Modify: `src/main/java/ru/pravets/vasyan/navigation/PathMonitor.java` (if needed)

**Interfaces:**
- Consumes: `VasyanPathNavigation` behavior.
- Produces: unchanged external API of `VasyanPathing.moveTo/enforce`.

- [ ] **Step 1: Review recovery ladder triggers.**

Because the new evaluator plans DIG/PLACE edges, the recovery ladder's DIG_THROUGH and PLACE_SCAFFOLD should rarely fire. Confirm `PathMonitor` still emits them as fallbacks when `canDig`/`canPlace` are true. No logic change is required unless tests fail.

- [ ] **Step 2: Re-run behavior scenarios A–J and fix any regressions.**

If a scenario now fails because recovery fires too early/too late, adjust `PathMonitor` stall thresholds or the evaluator cost weights. Prefer keeping P1 behavior unchanged.

- [ ] **Step 3: Commit any fixes.**

```bash
git add -A
git commit --author="Iosif Pravets <i@pravets.ru>" -m "fix(navigation): align recovery ladder with new path engine"
```

Only commit if there are changes; otherwise skip.

---

### Task 9: Add behavior scenario K

**Files:**
- Modify: `scripts/behavior/behavior_test.py`

**Interfaces:**
- Consumes: new navigation engine.
- Produces: scenario K in the behavior test suite.

- [ ] **Step 1: Build a combined obstacle course.**

Scenario K builds a flat corridor with:
1. A 2-block-high dirt wall.
2. A 2-block-wide pit deeper than `maxDropDown`.
3. A 2-block-high cliff.

Spawn the bot on one end, command `go to <x> <y> <z>` at the other end, and assert arrival. Also assert the log does **not** contain `DIG_THROUGH` or `PLACE_SCAFFOLD` after the initial path plan (use log tail checks already present in the script).

- [ ] **Step 2: Run behavior tests.**

```bash
python scripts/behavior/behavior_test.py
```
Expected: scenario K passes; A–J still pass.

- [ ] **Step 3: Commit.**

```bash
git add scripts/behavior/behavior_test.py
git commit --author="Iosif Pravets <i@pravets.ru>" -m "test(behavior): add scenario K for dig/place pathfinding"
```

---

### Task 10: Update documentation

**Files:**
- Modify: `docs/CAPABILITIES.md`
- Modify: `ROADMAP.md`

**Interfaces:**
- Consumes: final implementation behavior.
- Produces: docs reflect the new capabilities.

- [ ] **Step 1: Update `docs/CAPABILITIES.md`.**

Add a row/note under navigation: bots can now pathfind through breakable walls, bridge gaps, and pillar up short cliffs when they have blocks. Mention config keys.

- [ ] **Step 2: Update `ROADMAP.md`.**

Mark P2 under Phase 0.6 as completed / in review, and point to P3 as next.

- [ ] **Step 3: Commit.**

```bash
git add docs/CAPABILITIES.md ROADMAP.md
git commit --author="Iosif Pravets <i@pravets.ru>" -m "docs: document Phase 0.6 P2 dig/place pathfinding"
```

---

## Final Verification

Before claiming the task complete, run:

```bash
nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
python scripts/behavior/behavior_test.py
```

Expected: green.

Then do a final live Minecraft smoke test: spawn a bot, command it to walk around a wall, across a pit, and up a short cliff.

## Self-Review Checklist

- [ ] Each spec section (NodeEvaluator, PathFinder, PathNavigation, costs, safety, auto-replan, tests, docs) maps to at least one task.
- [ ] No TBD/TODO/filler language in plan steps.
- [ ] Method names are consistent across tasks (`vasyanEvaluator`, `getMoveType`, `digCost`, etc.).
- [ ] Each task ends with a testable deliverable and a commit.
- [ ] Existing P1 scenarios A–J are explicitly preserved.
