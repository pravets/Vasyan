# Phase 0.5 — Respawn bugfix

Approved 2026-08-22 with Иосиф Правец.

## Goal

Fix the bug where Vasyan bots respawn/appear near the player after server restart instead of staying at their saved position with saved inventory and memory.

## Current behavior (bug)

- After server restart and player relog, bots are near the player instead of where they were before restart.
- Position, inventory, and memory should be preserved in NBT and restored from the world.

## Desired behavior

- Bot saved at position P with inventory I and memory M before restart.
- After server restart and player relog, the same bot is adopted from the world at position P, with inventory I and memory M.
- Default bots (Vasyan, Alex, Bob, Charlie) are spawned only once per world; on subsequent logins they are adopted from the world, not re-spawned near the player.

## Root causes to investigate

1. `VasyanEntity.addAdditionalSaveData` / `readAdditionalSaveData` — verify name, memory, inventory are saved/loaded.
2. `VasyanWorldData` — verify the "default bots spawned" marker is correctly persisted across restarts.
3. `ServerEventHandler.onPlayerLoggedIn` — verify it does not spawn default bots again if the marker is set.
4. `VasyanManager.adopt` / `onVasyanUnload` — verify bots loaded from NBT are adopted and tracked correctly.
5. `VasyanEntity` persistence/AI — verify the entity is not teleported or moved on login.

## Implementation plan

### Task 1: Investigate current save/load path

- Read `VasyanEntity.java` NBT methods.
- Read `VasyanWorldData.java`.
- Read `ServerEventHandler.java` login handler.
- Read `VasyanManager.java` adopt/unload logic.
- Identify the exact cause of the respawn bug.

### Task 2: Fix the bug

- Make the minimal code change required to preserve position/inventory/memory across restarts.
- Ensure default bots are spawned only once per world.
- Do not break existing chunk force-load behavior or behavior tests.

### Task 3: Add regression tests

- Unit test: NBT round-trip preserves `VasyanName`, position (via super), `Inventory`, `Memory`, and `Staying`.
- Unit test: `VasyanWorldData` marker persists across save/load.
- Optional behavior-test log assertion: after restart, bot position is not at player spawn.

### Task 4: Verify

- `./gradlew clean compileJava compileTestJava` passes.
- `./gradlew test` passes.
- Behavior tests not required locally (CI runs them).

## Constraints

- One PR = one task (this whole bugfix is one PR).
- Local VPS build: `nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`.
- Full build + behavior tests in GitHub CI.
- Preserve MIT attribution.
- Commit author: `Iosif Pravets <i@pravets.ru>`.

## Deliverables

- Branch `feat/phase0-5-respawn-bugfix` from `master`.
- Commit with fix and tests.
- PR to `pravets/Vasyan`.
