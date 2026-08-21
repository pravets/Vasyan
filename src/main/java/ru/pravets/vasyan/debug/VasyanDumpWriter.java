package ru.pravets.vasyan.debug;

import ru.pravets.vasyan.action.ActionExecutor;
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
        String dimension = "unknown";
        if (level != null && level.dimension() != null && level.dimension().location() != null) {
            dimension = level.dimension().location().toString();
        }
        meta.addProperty("dimension", dimension);
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
        JsonObject vision = new JsonObject();
        try {
            VasyanEnvironmentScanner.SurfaceScan scan = VasyanEnvironmentScanner.scan(vasyan);
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
        } catch (Exception e) {
            vision.addProperty("error", "scan failed: " + e.getClass().getSimpleName());
        }
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
