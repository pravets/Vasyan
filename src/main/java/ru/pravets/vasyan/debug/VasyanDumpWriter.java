package ru.pravets.vasyan.debug;

import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.llm.PlanRecord;
import ru.pravets.vasyan.memory.VisionScanner;
import ru.pravets.vasyan.memory.VasyanMemory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        String baseName = safeName + "-" + timestamp;
        Path file = uniquePath(baseDir, baseName);
        JsonObject dump = buildDump(vasyan, includePrompt);
        Files.writeString(file, GSON.toJson(dump));
        writeViewerHtml(file, dump);
        return file;
    }

    private static Path uniquePath(Path baseDir, String baseName) {
        Path file = baseDir.resolve(baseName + ".json");
        if (!Files.exists(file)) {
            return file;
        }
        for (int i = 1; ; i++) {
            file = baseDir.resolve(baseName + "_" + i + ".json");
            if (!Files.exists(file)) {
                return file;
            }
        }
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
        meta.addProperty("scanRadius", VasyanConfig.WORLD_SCAN_RADIUS.get());
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
            JsonArray visibleBlocks = new JsonArray();
            for (var entry : VisionScanner.getVisibleBlocks(vasyan).entrySet()) {
                String id = BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString();
                for (BlockPos pos : entry.getValue()) {
                    JsonObject b = new JsonObject();
                    b.addProperty("id", id);
                    b.addProperty("x", pos.getX());
                    b.addProperty("y", pos.getY());
                    b.addProperty("z", pos.getZ());
                    visibleBlocks.add(b);
                }
            }
            vision.add("visibleBlocks", visibleBlocks);
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

    private static void writeViewerHtml(Path jsonFile, JsonObject dump) throws IOException {
        String template;
        try (var in = VasyanDumpWriter.class.getResourceAsStream("/vasyan-dump-3d-viewer.html")) {
            if (in == null) {
                return;
            }
            template = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String textures = buildTextureMap(dump);
        String json = GSON.toJson(dump);
        String html = template
            .replace("window.VASYAN_TEXTURES = {};", textures)
            .replace("window.VASYAN_DUMP = null;", "window.VASYAN_DUMP = " + json + ";");
        Path htmlFile = jsonFile.resolveSibling(jsonFile.getFileName().toString().replace(".json", ".html"));
        Files.writeString(htmlFile, html);
    }

    private static String buildTextureMap(JsonObject dump) {
        JsonObject vision = dump.getAsJsonObject("vision");
        if (vision == null) {
            return "window.VASYAN_TEXTURES = {};";
        }
        Set<String> blockIds = new java.util.HashSet<>();
        collectBlockIds(vision.getAsJsonArray("surfaceBlocks"), blockIds);
        collectBlockIds(vision.getAsJsonArray("visibleBlocks"), blockIds);

        StringBuilder sb = new StringBuilder("window.VASYAN_TEXTURES = {");
        boolean first = true;
        for (String blockId : blockIds) {
            String textureName = textureNameFor(blockId);
            if (textureName == null) {
                continue;
            }
            String path = "assets/minecraft/textures/block/" + textureName + ".png";
            try (var in = VasyanDumpWriter.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                byte[] bytes = in.readAllBytes();
                String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
                if (!first) sb.append(",");
                first = false;
                sb.append("\"")
                  .append(escapeJsString(blockId))
                  .append("\":\"data:image/png;base64,")
                  .append(b64)
                  .append("\"");
            } catch (IOException ignored) {
                // skip texture
            }
        }
        sb.append("};");
        return sb.toString();
    }

    private static void collectBlockIds(JsonArray blocks, Set<String> out) {
        if (blocks == null) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            JsonObject b = blocks.get(i).getAsJsonObject();
            JsonElement id = b.get("id");
            if (id != null && !id.isJsonNull()) {
                out.add(id.getAsString());
            }
        }
    }

    private static String textureNameFor(String blockId) {
        String name = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        return switch (name) {
            case "grass_block" -> "grass_block_top";
            case "dirt_path" -> "dirt_path_top";
            case "water" -> "water_still";
            case "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log" -> name;
            case "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "cherry_leaves" -> name;
            case "sugar_cane" -> "sugar_cane";
            case "tall_grass" -> "tall_grass_top";
            case "grass" -> "grass_block_top";
            case "poppy" -> "poppy";
            case "dandelion" -> "dandelion";
            case "blue_orchid" -> "blue_orchid";
            case "allium" -> "allium";
            case "azure_bluet" -> "azure_bluet";
            case "red_tulip", "orange_tulip", "white_tulip", "pink_tulip" -> name;
            case "oxeye_daisy" -> "oxeye_daisy";
            case "cornflower" -> "cornflower";
            case "lily_of_the_valley" -> "lily_of_the_valley";
            case "cobblestone", "stone", "deepslate", "gravel", "sand", "dirt", "bedrock" -> name;
            case "coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore", "lapis_ore", "diamond_ore", "emerald_ore" -> name;
            default -> name;
        };
    }

    private static String escapeJsString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
