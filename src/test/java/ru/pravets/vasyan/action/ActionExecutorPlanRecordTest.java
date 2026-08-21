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
