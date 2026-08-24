# Phase 1 Diagnostics & Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `/vasyan dump <name> [--with-prompt]` for a full bot-state snapshot and deterministic `/vasyan look <name>` + K-panel "что ты видишь?" for a brief environment description.

**Architecture:** Capture the last LLM prompt + raw response in a new `PlanRecord` held by `ActionExecutor`/`TaskPlanner`; reuse existing `VisionScanner` for visible-summary; add a deterministic `VasyanEnvironmentScanner` that outputs block positions for incident replay; route "look" phrases in `VasyanCommandDispatcher` before the LLM path; persist JSON dumps via `VasyanDumpWriter` to `logs/vasyan-dumps/<bot>-<timestamp>.json`.

**Tech Stack:** Minecraft 1.20.1 Forge, Java 17, Gson, JUnit 5 + Mockito, Gradle 8.4.

**Spec:**
- `docs/superpowers/specs/2026-08-21-vasyan-roadmap-design.md`
- `ROADMAP.md` section "Phase 1 — Diagnostics & visibility"

## Global Constraints

- Branch from `master` only: `feat/phase1-diagnostics-visibility`.
- Author commits as `Iosif Pravets <i@pravets.ru>`.
- Java 17, 4-space indentation, max line length 120, JavaDoc for public APIs.
- Local VPS build: `nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`.
- Full build + behavior tests in GitHub CI.
- One PR = one task; do not force-push after review has started unless agreed.
- Preserve upstream MIT attribution to `YuvDwi/Steve`.

---

## Task 1: Capture last LLM prompt + raw response

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/llm/PlanRecord.java`
- Modify: `src/main/java/ru/pravets/vasyan/llm/TaskPlanner.java` (capture record)
- Modify: `src/main/java/ru/pravets/vasyan/action/ActionExecutor.java` (expose getter)
- Test: `src/test/java/ru/pravets/vasyan/action/ActionExecutorPlanRecordTest.java`

**Interfaces:**
- Consumes: `LLMResponse` (existing `getContent()`, `getLatencyMs()`, `getModel()`, `isFromCache()`), `ResponseParser.ParsedResponse` (`getReasoning()`, `getPlan()`, `getTasks()`), `Task`.
- Produces: `PlanRecord` record + `ActionExecutor.getLastPlanRecord(): PlanRecord`.

- [ ] **Step 1: Write the failing test**

```java
package ru.pravets.vasyan.action;

import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.llm.PlanRecord;
import ru.pravets.vasyan.llm.ResponseParser;
import ru.pravets.vasyan.memory.VasyanMemory;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionExecutorPlanRecordTest extends AbstractMinecraftTest {

    @BeforeAll
    static void loadVasyanConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        VasyanConfig.SPEC.correct(config);
        VasyanConfig.SPEC.acceptConfig(config);
    }

    @Test
    void getLastPlanRecordReturnsNullBeforeAnyPlanning() {
        VasyanEntity vasyan = mock(VasyanEntity.class);
        Level level = mock(Level.class);
        PathNavigation navigation = mock(PathNavigation.class);
        VasyanMemory memory = mock(VasyanMemory.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.players()).thenReturn(Collections.emptyList());
        when(vasyan.level()).thenReturn(level);
        when(vasyan.getVasyanName()).thenReturn("TestVasyan");
        when(vasyan.getNavigation()).thenReturn(navigation);
        when(vasyan.getMemory()).thenReturn(memory);

        ActionExecutor executor = new ActionExecutor(vasyan);

        assertNull(executor.getLastPlanRecord());
    }

    @Test
    void storesCompletedPlanRecord() {
        VasyanEntity vasyan = mock(VasyanEntity.class);
        Level level = mock(Level.class);
        PathNavigation navigation = mock(PathNavigation.class);
        VasyanMemory memory = mock(VasyanMemory.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.players()).thenReturn(Collections.emptyList());
        when(level.getGameTime()).thenReturn(0L);
        when(vasyan.level()).thenReturn(level);
        when(vasyan.getVasyanName()).thenReturn("TestVasyan");
        when(vasyan.getNavigation()).thenReturn(navigation);
        when(vasyan.getMemory()).thenReturn(memory);

        ActionExecutor executor = new ActionExecutor(vasyan);
        ResponseParser.ParsedResponse parsed = new ResponseParser.ParsedResponse(
            "reason", "plan", Collections.emptyList());
        executor.setPlanningFutureForTest(CompletableFuture.completedFuture(parsed), "look around");

        for (int i = 0; i < 5; i++) {
            executor.tick();
        }

        PlanRecord record = executor.getLastPlanRecord();
        assertNotNull(record);
        assertEquals("look around", record.command());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd ~/Vasyan-phase1
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.action.ActionExecutorPlanRecordTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: compilation fails because `PlanRecord` and `ActionExecutor.getLastPlanRecord()` do not exist.

- [ ] **Step 3: Create `PlanRecord.java`**

```java
package ru.pravets.vasyan.llm;

import ru.pravets.vasyan.action.Task;

import java.util.Collections;
import java.util.List;

/**
 * Snapshot of one LLM planning round: the original command, the prompts sent,
 * the raw LLM response, the parsed plan and request metadata.
 */
public record PlanRecord(
    String command,
    String systemPrompt,
    String userPrompt,
    String rawResponse,
    String reasoning,
    String plan,
    List<Task> tasks,
    long latencyMs,
    String model,
    boolean fromCache
) {
    public PlanRecord {
        tasks = tasks != null ? List.copyOf(tasks) : Collections.emptyList();
    }
}
```

- [ ] **Step 4: Modify `TaskPlanner` to capture the record**

Add field and getter:

```java
private volatile PlanRecord lastPlanRecord;

public PlanRecord getLastPlanRecord() {
    return lastPlanRecord;
}
```

In `planTasksAsync`, inside the `thenApply` that processes the `LLMResponse`, after parsing succeeds, store the record. The `systemPrompt` and `userPrompt` variables are already in scope before the async call.

```java
lastPlanRecord = new PlanRecord(
    command,
    systemPrompt,
    userPrompt,
    content,
    parsed.getReasoning(),
    parsed.getPlan(),
    parsed.getTasks(),
    response.getLatencyMs(),
    response.getModel(),
    response.isFromCache()
);
```

Do the same in the synchronous `planTasks` method.

- [ ] **Step 5: Modify `ActionExecutor` to expose the record**

Add:

```java
public PlanRecord getLastPlanRecord() {
    TaskPlanner planner = getTaskPlannerIfInitialized();
    return planner != null ? planner.getLastPlanRecord() : null;
}

private TaskPlanner getTaskPlannerIfInitialized() {
    return taskPlanner;
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.action.ActionExecutorPlanRecordTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ru/pravets/vasyan/llm/PlanRecord.java \
        src/main/java/ru/pravets/vasyan/llm/TaskPlanner.java \
        src/main/java/ru/pravets/vasyan/action/ActionExecutor.java \
        src/test/java/ru/pravets/vasyan/action/ActionExecutorPlanRecordTest.java
git commit -m "feat(phase1): capture last LLM prompt and raw response in PlanRecord"
```

---

## Task 2: Deterministic surface/environment scanner

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/debug/VasyanEnvironmentScanner.java`
- Modify: `src/main/java/ru/pravets/vasyan/memory/VisionScanner.java` (only if a public helper for direction/distance is missing — otherwise reuse)
- Test: `src/test/java/ru/pravets/vasyan/debug/VasyanEnvironmentScannerTest.java`

**Interfaces:**
- Consumes: `VasyanEntity`, `Level`, `VisionScanner.getVisibleSummary(VasyanEntity)`.
- Produces: `VasyanEnvironmentScanner.scan(VasyanEntity): SurfaceScan` and `describe(SurfaceScan): String`.

- [ ] **Step 1: Write the failing test**

```java
package ru.pravets.vasyan.debug;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VasyanEnvironmentScannerTest {

    @Test
    void describeFormatsBiomeAndBlocks() {
        VasyanEnvironmentScanner.SurfaceScan scan = new VasyanEnvironmentScanner.SurfaceScan(
            "minecraft:plains",
            6000L,
            true,
            false,
            "oak_log x3 (12m S), iron_ore (8m down)",
            List.of(new VasyanEnvironmentScanner.BlockEntry("grass_block", 1, 64, 2),
                    new VasyanEnvironmentScanner.BlockEntry("oak_log", 5, 65, -3)),
            List.of(new VasyanEnvironmentScanner.EntityEntry("zombie", null, 10.0, "E"))
        );

        String description = VasyanEnvironmentScanner.describe(scan);

        assertTrue(description.contains("равнины") || description.contains("plains"),
            "Should mention biome");
        assertTrue(description.contains("дуб") || description.contains("oak"),
            "Should mention oak");
        assertTrue(description.contains("зомби") || description.contains("zombie"),
            "Should mention zombie");
    }

    @Test
    void emptyScanIsHonest() {
        VasyanEnvironmentScanner.SurfaceScan scan = new VasyanEnvironmentScanner.SurfaceScan(
            "minecraft:plains", 6000L, false, false, "nothing interesting",
            List.of(), List.of());

        String description = VasyanEnvironmentScanner.describe(scan);

        assertTrue(description.contains("ничего") || description.contains("nothing"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.debug.VasyanEnvironmentScannerTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: compilation fails because `VasyanEnvironmentScanner` does not exist.

- [ ] **Step 3: Implement `VasyanEnvironmentScanner`**

```java
package ru.pravets.vasyan.debug;

import ru.pravets.vasyan.memory.VisionScanner;
import ru.pravets.vasyan.entity.VasyanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic, LLM-free scanner that describes what a Vasyan can see around
 * itself. Used by {@code /vasyan look} and by the full-state dump.
 */
public final class VasyanEnvironmentScanner {

    private static final int SURFACE_RADIUS = 16;
    private static final int SURFACE_MAX_BLOCKS = 256;

    public record BlockEntry(String blockId, int x, int y, int z) {}
    public record EntityEntry(String type, String name, double distance, String direction) {}

    public record SurfaceScan(
        String biome,
        long dayTime,
        boolean raining,
        boolean thundering,
        String visibleSummary,
        List<BlockEntry> surfaceBlocks,
        List<EntityEntry> nearbyEntities
    ) {}

    private VasyanEnvironmentScanner() {}

    /**
     * Scans the world around the given Vasyan. Runs on the server thread.
     */
    public static SurfaceScan scan(VasyanEntity vasyan) {
        Level level = vasyan.level();
        BlockPos origin = vasyan.blockPosition();

        String biome = level.getBiome(origin).unwrapKey()
            .map(k -> k.location().toString())
            .orElse("unknown");

        List<BlockEntry> surfaceBlocks = collectSurface(level, origin);
        List<EntityEntry> entities = collectEntities(vasyan, origin);

        return new SurfaceScan(
            biome,
            level.getDayTime(),
            level.isRaining(),
            level.isThundering(),
            VisionScanner.getVisibleSummary(vasyan),
            surfaceBlocks,
            entities
        );
    }

    /**
     * Formats a {@link SurfaceScan} as a short, human-readable sentence in
     * Russian (the project's default UI language). Keep under ~200 characters.
     */
    public static String describe(SurfaceScan scan) {
        StringBuilder sb = new StringBuilder();
        String biomeShort = scan.biome().contains(":") ? scan.biome().substring(scan.biome().indexOf(':') + 1) : scan.biome();
        sb.append("Я вижу ").append(biomeShort);
        if (scan.surfaceBlocks().isEmpty()) {
            sb.append(", вокруг пусто");
        } else {
            sb.append(", вокруг: ").append(shortBlockList(scan.surfaceBlocks()));
        }
        if (!scan.nearbyEntities().isEmpty()) {
            sb.append(". Рядом: ").append(shortEntityList(scan.nearbyEntities()));
        }
        String visible = scan.visibleSummary();
        if (visible != null && !visible.isEmpty() && !"nothing interesting".equals(visible)) {
            sb.append(". Замечаю ").append(visible);
        }
        return sb.toString();
    }

    private static List<BlockEntry> collectSurface(Level level, BlockPos origin) {
        List<BlockEntry> entries = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int dx = -SURFACE_RADIUS; dx <= SURFACE_RADIUS && entries.size() < SURFACE_MAX_BLOCKS; dx += 2) {
            for (int dz = -SURFACE_RADIUS; dz <= SURFACE_RADIUS && entries.size() < SURFACE_MAX_BLOCKS; dz += 2) {
                mutable.set(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
                if (!level.hasChunkAt(mutable)) {
                    continue;
                }
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, mutable);
                if (surface == null) {
                    continue;
                }
                BlockState state = level.getBlockState(surface);
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                String blockId = id != null ? id.toString() : state.getBlock().toString();
                entries.add(new BlockEntry(blockId, surface.getX(), surface.getY(), surface.getZ()));
            }
        }
        return entries;
    }

    private static List<EntityEntry> collectEntities(VasyanEntity vasyan, BlockPos origin) {
        List<EntityEntry> entries = new ArrayList<>();
        List<Entity> nearby = vasyan.level().getEntities(vasyan, vasyan.getBoundingBox().inflate(24.0),
            e -> e instanceof LivingEntity && !(e instanceof VasyanEntity));
        for (Entity e : nearby) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            String name = e instanceof Player p ? p.getName().getString() : null;
            double dist = Math.sqrt(e.distanceToSqr(vasyan));
            String dir = direction(origin, e.blockPosition());
            entries.add(new EntityEntry(type, name, dist, dir));
        }
        entries.sort(Comparator.comparingDouble(EntityEntry::distance));
        return entries.size() > 8 ? entries.subList(0, 8) : entries;
    }

    private static String shortBlockList(List<BlockEntry> entries) {
        // Use the most common surface block names, deduplicated, limited to 4.
        return entries.stream()
            .map(BlockEntry::blockId)
            .map(id -> id.contains(":") ? id.substring(id.indexOf(':') + 1) : id)
            .distinct()
            .limit(4)
            .reduce((a, b) -> a + ", " + b)
            .orElse("блоки");
    }

    private static String shortEntityList(List<EntityEntry> entries) {
        if (entries.isEmpty()) return "";
        EntityEntry first = entries.get(0);
        String type = first.type().contains(":") ? first.type().substring(first.type().indexOf(':') + 1) : first.type();
        String label = first.name() != null ? first.name() : type;
        if (entries.size() == 1) {
            return label + " (~" + Math.round(first.distance()) + "m " + first.direction() + ")";
        }
        return label + " и ещё " + (entries.size() - 1);
    }

    private static String direction(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) return "here";
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        if (angle < 0) angle += 360;
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.round(angle / 45) % 8];
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.debug.VasyanEnvironmentScannerTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/pravets/vasyan/debug/VasyanEnvironmentScanner.java \
        src/test/java/ru/pravets/vasyan/debug/VasyanEnvironmentScannerTest.java
git commit -m "feat(phase1): deterministic environment scanner for look and dump"
```

---

## Task 3: JSON dump writer

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/debug/VasyanDumpWriter.java`
- Test: `src/test/java/ru/pravets/vasyan/debug/VasyanDumpWriterTest.java`

**Interfaces:**
- Consumes: `VasyanEntity`, `VasyanInventory`, `VasyanMemory`, `ActionExecutor`, `PlanRecord`, `VasyanEnvironmentScanner.SurfaceScan`, Gson.
- Produces: `VasyanDumpWriter.write(VasyanEntity, boolean): Path` and `write(VasyanEntity, boolean, Path): Path`.

- [ ] **Step 1: Write the failing test**

```java
package ru.pravets.vasyan.debug;

import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.llm.PlanRecord;
import ru.pravets.vasyan.memory.VasyanMemory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VasyanDumpWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesDumpWithoutPromptByDefault() throws IOException {
        VasyanEntity vasyan = mock(VasyanEntity.class);
        Level level = mock(Level.class);
        ActionExecutor executor = mock(ActionExecutor.class);
        VasyanMemory memory = mock(VasyanMemory.class);
        VasyanInventory inventory = new VasyanInventory(null, 27);

        when(vasyan.level()).thenReturn(level);
        when(vasyan.getVasyanName()).thenReturn("Bob");
        when(vasyan.getUUID()).thenReturn(UUID.randomUUID());
        when(vasyan.blockPosition()).thenReturn(new BlockPos(100, 64, -200));
        when(vasyan.getXRot()).thenReturn(0f);
        when(vasyan.getYRot()).thenReturn(0f);
        when(vasyan.getHealth()).thenReturn(20f);
        when(vasyan.getActionExecutor()).thenReturn(executor);
        when(executor.getStateSummary()).thenReturn("idle");
        when(executor.getLastPlanRecord()).thenReturn(new PlanRecord(
            "look around", "sys", "user", "raw", "reason", "plan", Collections.emptyList(), 123, "m", false));
        when(vasyan.getMemory()).thenReturn(memory);
        when(memory.getCurrentGoal()).thenReturn("");
        when(memory.getRecentActions(20)).thenReturn(Collections.emptyList());
        when(vasyan.getInventory()).thenReturn(inventory);

        Path file = VasyanDumpWriter.write(vasyan, false, tempDir);

        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(root.has("meta"));
        assertTrue(root.has("llm"));
        assertFalse(root.getAsJsonObject("llm").has("prompt"), "Prompt must be omitted without flag");
        assertTrue(root.getAsJsonObject("llm").has("rawResponse"));
    }

    @Test
    void includesPromptWhenFlagSet() throws IOException {
        VasyanEntity vasyan = mock(VasyanEntity.class);
        Level level = mock(Level.class);
        ActionExecutor executor = mock(ActionExecutor.class);
        VasyanMemory memory = mock(VasyanMemory.class);
        VasyanInventory inventory = new VasyanInventory(null, 27);

        when(vasyan.level()).thenReturn(level);
        when(vasyan.getVasyanName()).thenReturn("Bob");
        when(vasyan.getUUID()).thenReturn(UUID.randomUUID());
        when(vasyan.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
        when(vasyan.getXRot()).thenReturn(0f);
        when(vasyan.getYRot()).thenReturn(0f);
        when(vasyan.getHealth()).thenReturn(20f);
        when(vasyan.getActionExecutor()).thenReturn(executor);
        when(executor.getStateSummary()).thenReturn("idle");
        when(executor.getLastPlanRecord()).thenReturn(new PlanRecord(
            "look around", "sys prompt", "user prompt", "raw", "reason", "plan", Collections.emptyList(), 123, "m", false));
        when(vasyan.getMemory()).thenReturn(memory);
        when(memory.getCurrentGoal()).thenReturn("");
        when(memory.getRecentActions(20)).thenReturn(Collections.emptyList());
        when(vasyan.getInventory()).thenReturn(inventory);

        Path file = VasyanDumpWriter.write(vasyan, true, tempDir);

        String json = Files.readString(file);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("sys prompt", root.getAsJsonObject("llm").get("systemPrompt").getAsString());
        assertEquals("user prompt", root.getAsJsonObject("llm").get("userPrompt").getAsString());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.debug.VasyanDumpWriterTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: compilation fails because `VasyanDumpWriter` does not exist.

- [ ] **Step 3: Implement `VasyanDumpWriter`**

```java
package ru.pravets.vasyan.debug;

import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.debug.AgentDebugBuffer;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.llm.PlanRecord;
import ru.pravets.vasyan.memory.VasyanMemory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes a full bot-state snapshot to {@code logs/vasyan-dumps/<bot>-<timestamp>.json}.
 */
public final class VasyanDumpWriter {

    private static final DateTimeFormatter FILENAME_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private VasyanDumpWriter() {}

    /**
     * Writes a dump to {@code logs/vasyan-dumps/<bot>-<timestamp>.json}.
     *
     * @param vasyan       the bot to dump
     * @param includePrompt whether to include the last system/user prompts
     * @return the path of the written file
     */
    public static Path write(VasyanEntity vasyan, boolean includePrompt) throws IOException {
        return write(vasyan, includePrompt, Path.of("logs/vasyan-dumps"));
    }

    /**
     * Writes a dump to a configurable base directory (used by tests).
     */
    public static Path write(VasyanEntity vasyan, boolean includePrompt, Path baseDir) throws IOException {
        Files.createDirectories(baseDir);
        String timestamp = LocalDateTime.now().format(FILENAME_TIME);
        String safeName = vasyan.getVasyanName().replaceAll("[/\\\\]", "_");
        Path file = baseDir.resolve(safeName + "-" + timestamp + ".json");
        JsonObject dump = buildDump(vasyan, includePrompt);
        Files.writeString(file, GSON.toJson(dump));
        return file;
    }

    private static JsonObject buildDump(VasyanEntity vasyan, boolean includePrompt) {
        JsonObject root = new JsonObject();
        root.add("meta", buildMeta(vasyan));
        root.add("state", buildState(vasyan));
        root.add("memory", buildMemory(vasyan.getMemory()));
        root.add("inventory", buildInventory(vasyan.getInventory()));
        root.add("llm", buildLlm(vasyan.getActionExecutor(), includePrompt));
        root.add("vision", buildVision(vasyan));
        root.add("debug", buildDebug());
        return root;
    }

    private static JsonObject buildMeta(VasyanEntity vasyan) {
        JsonObject meta = new JsonObject();
        meta.addProperty("name", vasyan.getVasyanName());
        meta.addProperty("uuid", vasyan.getUUID().toString());
        BlockPos pos = vasyan.blockPosition();
        meta.addProperty("x", pos.getX());
        meta.addProperty("y", pos.getY());
        meta.addProperty("z", pos.getZ());
        meta.addProperty("pitch", vasyan.getXRot());
        meta.addProperty("yaw", vasyan.getYRot());
        meta.addProperty("health", vasyan.getHealth());
        Level level = vasyan.level();
        meta.addProperty("dimension", level.dimension().location().toString());
        meta.addProperty("timestamp", LocalDateTime.now().toString());
        return meta;
    }

    private static JsonObject buildState(VasyanEntity vasyan) {
        JsonObject state = new JsonObject();
        ActionExecutor executor = vasyan.getActionExecutor();
        state.addProperty("summary", executor.getStateSummary());
        state.addProperty("staying", executor.isStaying());
        return state;
    }

    private static JsonObject buildMemory(VasyanMemory memory) {
        JsonObject mem = new JsonObject();
        mem.addProperty("currentGoal", memory.getCurrentGoal());
        JsonArray recent = new JsonArray();
        for (String action : memory.getRecentActions(20)) {
            recent.add(action);
        }
        mem.add("recentActions", recent);
        return mem;
    }

    private static JsonObject buildInventory(VasyanInventory inventory) {
        JsonObject inv = new JsonObject();
        inv.addProperty("capacity", inventory.getMaxSize());
        inv.addProperty("stacksCount", inventory.getStacksCount());
        inv.addProperty("totalItems", inventory.getTotalCount());
        JsonArray stacks = new JsonArray();
        for (ItemStack stack : inventory.getStacks()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", stack.getItem().toString());
            entry.addProperty("count", stack.getCount());
            stacks.add(entry);
        }
        inv.add("stacks", stacks);
        return inv;
    }

    private static JsonObject buildLlm(ActionExecutor executor, boolean includePrompt) {
        JsonObject llm = new JsonObject();
        PlanRecord record = executor.getLastPlanRecord();
        if (record != null) {
            if (includePrompt) {
                llm.addProperty("systemPrompt", record.systemPrompt());
                llm.addProperty("userPrompt", record.userPrompt());
            }
            llm.addProperty("rawResponse", record.rawResponse());
            llm.addProperty("reasoning", record.reasoning());
            llm.addProperty("plan", record.plan());
            llm.addProperty("model", record.model());
            llm.addProperty("fromCache", record.fromCache());
            llm.addProperty("latencyMs", record.latencyMs());
        }
        return llm;
    }

    private static JsonObject buildVision(VasyanEntity vasyan) {
        VasyanEnvironmentScanner.SurfaceScan scan = VasyanEnvironmentScanner.scan(vasyan);
        JsonObject vision = new JsonObject();
        vision.addProperty("biome", scan.biome());
        vision.addProperty("dayTime", scan.dayTime());
        vision.addProperty("raining", scan.raining());
        vision.addProperty("thundering", scan.thundering());
        vision.addProperty("summary", VasyanEnvironmentScanner.describe(scan));
        JsonArray blocks = new JsonArray();
        for (VasyanEnvironmentScanner.BlockEntry entry : scan.surfaceBlocks()) {
            JsonObject b = new JsonObject();
            b.addProperty("id", entry.blockId());
            b.addProperty("x", entry.x());
            b.addProperty("y", entry.y());
            b.addProperty("z", entry.z());
            blocks.add(b);
        }
        vision.add("surfaceBlocks", blocks);
        JsonArray entities = new JsonArray();
        for (VasyanEnvironmentScanner.EntityEntry entry : scan.nearbyEntities()) {
            JsonObject e = new JsonObject();
            e.addProperty("type", entry.type());
            if (entry.name() != null) e.addProperty("name", entry.name());
            e.addProperty("distance", entry.distance());
            e.addProperty("direction", entry.direction());
            entities.add(e);
        }
        vision.add("nearbyEntities", entities);
        return vision;
    }

    private static JsonArray buildDebug() {
        JsonArray arr = new JsonArray();
        List<String> events = AgentDebugBuffer.getEvents(50);
        for (String event : events) {
            arr.add(event);
        }
        return arr;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.debug.VasyanDumpWriterTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/pravets/vasyan/debug/VasyanDumpWriter.java \
        src/test/java/ru/pravets/vasyan/debug/VasyanDumpWriterTest.java
git commit -m "feat(phase1): add VasyanDumpWriter for full-state JSON snapshots"
```

---

## Task 4: Wire `/vasyan dump` and `/vasyan look` commands

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/command/VasyanCommands.java`
- Test: `src/test/java/ru/pravets/vasyan/command/VasyanCommandsTest.java`

**Interfaces:**
- Consumes: `VasyanDumpWriter.write(VasyanEntity, boolean)`, `VasyanEnvironmentScanner.scan/describe`, `VasyanNameArgumentType`.
- Produces: two new command handlers registered under `/vasyan dump` and `/vasyan look`.

- [ ] **Step 1: Write the failing test**

```java
package ru.pravets.vasyan.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class VasyanCommandsTest {

    private static final CommandSourceStack SOURCE = mock(CommandSourceStack.class);
    private static CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void setUp() {
        dispatcher = new CommandDispatcher<>();
        VasyanCommands.register(dispatcher);
    }

    @Test
    void parsesDumpCommand() {
        assertParses("vasyan dump Bob");
        assertParses("vasyan dump Bob with-prompt");
    }

    @Test
    void parsesLookCommand() {
        assertParses("vasyan look Bob");
    }

    private static void assertParses(String command) {
        ParseResults<CommandSourceStack> results = dispatcher.parse(command, SOURCE);
        assertNotNull(results.getContext().getCommand(),
            "Command should parse successfully: " + command);
        assertFalse(results.getReader().canRead(),
            "Command should consume all input: " + command);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.command.VasyanCommandsTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: test fails because `dump` and `look` literals are not registered.

- [ ] **Step 3: Register the commands in `VasyanCommands`**

Add inside the `dispatcher.register(Commands.literal("vasyan")` chain, next to the existing `inventory` literal:

```java
.then(Commands.literal("dump")
    .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
        .executes(ctx -> dumpVasyan(ctx, false))
        .then(Commands.literal("with-prompt")
            .executes(ctx -> dumpVasyan(ctx, true)))))
.then(Commands.literal("look")
    .then(Commands.argument("name", VasyanNameArgumentType.vasyanName())
        .executes(VasyanCommands::lookVasyan)))
```

Add handler methods at the end of the class:

```java
private static int dumpVasyan(CommandContext<CommandSourceStack> context, boolean includePrompt) {
    String name = VasyanNameArgumentType.getName(context, "name");
    CommandSourceStack source = context.getSource();

    VasyanEntity vasyan = VasyanMod.getVasyanManager().getVasyan(name);
    if (vasyan == null) {
        source.sendFailure(Component.literal("§cVasyan not found: " + name));
        return 0;
    }

    try {
        Path file = VasyanDumpWriter.write(vasyan, includePrompt);
        source.sendSuccess(() -> Component.literal(
            "§aDumped " + name + " to §f" + file), false);
        return 1;
    } catch (IOException e) {
        VasyanMod.LOGGER.error("Failed to write dump for {}", name, e);
        source.sendFailure(Component.literal("§cFailed to write dump: " + e.getMessage()));
        return 0;
    }
}

private static int lookVasyan(CommandContext<CommandSourceStack> context) {
    String name = VasyanNameArgumentType.getName(context, "name");
    CommandSourceStack source = context.getSource();

    VasyanEntity vasyan = VasyanMod.getVasyanManager().getVasyan(name);
    if (vasyan == null) {
        source.sendFailure(Component.literal("§cVasyan not found: " + name));
        return 0;
    }

    VasyanEnvironmentScanner.SurfaceScan scan = VasyanEnvironmentScanner.scan(vasyan);
    String description = VasyanEnvironmentScanner.describe(scan);
    vasyan.sendChatMessage(description);
    source.sendSuccess(() -> Component.literal("§7" + name + " looks around"), false);
    return 1;
}
```

Add imports:

```java
import ru.pravets.vasyan.debug.VasyanDumpWriter;
import ru.pravets.vasyan.debug.VasyanEnvironmentScanner;
import java.nio.file.Path;
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.command.VasyanCommandsTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/pravets/vasyan/command/VasyanCommands.java \
        src/test/java/ru/pravets/vasyan/command/VasyanCommandsTest.java
git commit -m "feat(phase1): add /vasyan dump and /vasyan look commands"
```

---

## Task 5: Route "что ты видишь?" from K-panel without LLM

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/chat/ChatCommandParser.java`
- Modify: `src/main/java/ru/pravets/vasyan/command/VasyanCommandDispatcher.java`
- Test: `src/test/java/ru/pravets/vasyan/chat/ChatCommandParserTest.java`

**Interfaces:**
- Consumes: `ChatCommandParser.isLookCommand(String)`, `VasyanEnvironmentScanner`.
- Produces: deterministic look response from K-panel / voice commands.

- [ ] **Step 1: Write the failing test**

Extend `src/test/java/ru/pravets/vasyan/chat/ChatCommandParserTest.java`:

```java
@Test
void lookCommands() {
    assertTrue(ChatCommandParser.isLookCommand(normalize("что ты видишь")));
    assertTrue(ChatCommandParser.isLookCommand(normalize("что видишь")));
    assertTrue(ChatCommandParser.isLookCommand(normalize("what do you see")));
    assertTrue(ChatCommandParser.isLookCommand(normalize("look around")));
    assertTrue(ChatCommandParser.isLookCommand(normalize("look")));
    assertFalse(ChatCommandParser.isLookCommand(normalize("mine iron")));
    assertFalse(ChatCommandParser.isLookCommand(normalize("")));
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.chat.ChatCommandParserTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: compilation fails because `isLookCommand` does not exist.

- [ ] **Step 3: Add `isLookCommand` to `ChatCommandParser`**

Add marker list and method:

```java
private static final List<String> LOOK_MARKERS = List.of(
    "что ты видишь", "что видишь", "что ты тут видишь", "что вокруг",
    "look around", "what do you see", "what you see", "look"
);

public static boolean isLookCommand(String lowerCommand) {
    if (lowerCommand == null) {
        return false;
    }
    String trimmed = lowerCommand.trim().toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) {
        return false;
    }
    return LOOK_MARKERS.stream().anyMatch(trimmed::startsWith);
}
```

- [ ] **Step 4: Add look trigger in `VasyanCommandDispatcher`**

In `deliver(VasyanEntity vasyan, String command, CommandSourceStack source)`, after the stay check and before scheduling LLM work:

```java
if (ChatCommandParser.isLookCommand(lower)) {
    String description = VasyanEnvironmentScanner.describe(VasyanEnvironmentScanner.scan(vasyan));
    vasyan.sendChatMessage(description);
    source.sendSuccess(() -> Component.literal("§7" + vasyan.getVasyanName() + " looks around"), false);
    return;
}
```

Add import:

```java
import ru.pravets.vasyan.debug.VasyanEnvironmentScanner;
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
nice -n 19 ionice -c3 ./gradlew test --tests "ru.pravets.vasyan.chat.ChatCommandParserTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/pravets/vasyan/chat/ChatCommandParser.java \
        src/main/java/ru/pravets/vasyan/command/VasyanCommandDispatcher.java \
        src/test/java/ru/pravets/vasyan/chat/ChatCommandParserTest.java
git commit -m "feat(phase1): route K-panel look phrases deterministically"
```

---

## Task 6: Full build, behavior tests and PR

**Files:**
- All modified files above.
- Update: `docs/USAGE.ru.md` and `docs/USAGE.en.md` with new commands.

- [ ] **Step 1: Update usage docs**

Add to `docs/USAGE.ru.md` in the "Что умеют боты" / commands section:

```markdown
- `/vasyan dump <имя> [with-prompt]` — сохранить полное состояние бота в `logs/vasyan-dumps/<bot>-<timestamp>.json`.
- `/vasyan look <имя>` или «что ты видишь?» в панели K — краткое детерминированное описание окружения.
```

Mirror in `docs/USAGE.en.md`:

```markdown
- `/vasyan dump <name> [with-prompt]` — save full bot state to `logs/vasyan-dumps/<bot>-<timestamp>.json`.
- `/vasyan look <name>` or "what do you see?" in the K panel — brief deterministic environment description.
```

- [ ] **Step 2: Run local compile + tests**

```bash
nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full test suite**

```bash
nice -n 19 ionice -c3 ./gradlew test --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: all tests pass.

- [ ] **Step 4: Run behavior tests**

```bash
nice -n 19 ionice -c3 ./gradlew behaviorTest --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

If the task does not exist or fails, run the script referenced by the project's behavior-test setup. Expected: no regressions.

- [ ] **Step 5: Push branch and open PR**

```bash
git push -u origin feat/phase1-diagnostics-visibility
gh -R pravets/Vasyan pr create --title "feat(phase1): diagnostics and visibility commands" \
    --body "Adds /vasyan dump, /vasyan look and K-panel 'что ты видишь?' deterministic handler. Closes Phase 1 of roadmap."
```

- [ ] **Step 6: Commit doc updates**

```bash
git add docs/USAGE.ru.md docs/USAGE.en.md
git commit -m "docs(phase1): document dump and look commands"
```

---

## Self-Review

**Spec coverage:**
- `/vasyan dump <name>` with default LLM response and `with-prompt` flag → Task 3 + Task 4.
- Dump location `logs/vasyan-dumps/<bot>-<timestamp>.json` → Task 3.
- Surface scan for incident visualization → Task 2 (surfaceBlocks with coordinates).
- «что ты видишь?» command → Task 2 + Task 5.
- Branch from master, one PR, CI green → Task 6.

**Placeholder scan:** No TBD/TODO/fill-in details. Every step contains concrete code or exact command.

**Type consistency:** `PlanRecord` fields match `TaskPlanner` capture and `VasyanDumpWriter` consumption. `SurfaceScan`/`BlockEntry`/`EntityEntry` are defined and used only inside `VasyanEnvironmentScanner`. `VasyanDumpWriter.write(VasyanEntity, boolean, Path)` overload is used in tests.

**Gaps identified and fixed:**
- Prompt capture requires `TaskPlanner` change — covered in Task 1.
- `VasyanEntity.getUUID()` and `blockPosition()` are used in dump — verified available on entity.
- Need test-safe overload for dump path — included in Task 3.

**Risk notes:**
- `Level.getHeightmapPos` and `level.getBiome` are registry-touching; if unit tests ever call them without bootstrap, extend `AbstractMinecraftTest`. The scanner unit tests only test `describe(SurfaceScan)`, which is pure.
- `VasyanDumpWriterTest` mocks `VasyanEntity` and does not touch registries.
