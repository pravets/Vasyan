package ru.pravets.vasyan.debug;

import ru.pravets.vasyan.action.ActionExecutor;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.entity.VasyanInventory;
import ru.pravets.vasyan.llm.PlanRecord;
import ru.pravets.vasyan.memory.VasyanMemory;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
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

class VasyanDumpWriterTest extends AbstractMinecraftTest {

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
        when(level.dimension()).thenReturn(Level.OVERWORLD);
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
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(vasyan.getVasyanName()).thenReturn("Bob");
        when(vasyan.getUUID()).thenReturn(UUID.randomUUID());
        when(vasyan.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
        when(vasyan.getXRot()).thenReturn(0f);
        when(vasyan.getYRot()).thenReturn(0f);
        when(vasyan.getHealth()).thenReturn(20f);
        when(vasyan.getActionExecutor()).thenReturn(executor);
        when(executor.getStateSummary()).thenReturn("idle");
        when(executor.getLastPlanRecord()).thenReturn(new PlanRecord(
            "look around",
            "sys prompt",
            "user prompt",
            "raw",
            "reason",
            "plan",
            Collections.emptyList(),
            123,
            "m",
            false));
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
