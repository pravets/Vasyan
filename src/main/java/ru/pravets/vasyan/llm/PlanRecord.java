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
