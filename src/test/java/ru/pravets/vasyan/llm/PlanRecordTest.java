package ru.pravets.vasyan.llm;

import ru.pravets.vasyan.action.Task;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanRecordTest {

    @Test
    void storesDeepSnapshotOfTasks() {
        Map<String, Object> params = new HashMap<>();
        params.put("quantity", 10);
        Task original = new Task("gather", params);

        PlanRecord record = new PlanRecord(
            "gather wood", "sys", "user", "raw",
            "reason", "plan", List.of(original), 100, "m", false);

        assertEquals(1, record.tasks().size());
        assertEquals("gather", record.tasks().get(0).getAction());
        assertEquals(10, record.tasks().get(0).getParameter("quantity"));

        // Mutating the original task and its parameters must not affect the
        // snapshot held by PlanRecord.
        params.put("quantity", 99);
        original.getParameters().put("fill", true);

        assertEquals(10, record.tasks().get(0).getParameter("quantity"),
            "PlanRecord must keep the original quantity, not the mutated one");
        assertNull(record.tasks().get(0).getParameter("fill"),
            "PlanRecord must not pick up parameters added after creation");
    }
}
