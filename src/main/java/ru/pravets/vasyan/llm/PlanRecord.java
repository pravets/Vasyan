package ru.pravets.vasyan.llm;

import ru.pravets.vasyan.action.Task;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        tasks = tasks != null
            ? tasks.stream()
                .map(t -> new Task(t.getAction(), Map.copyOf(new java.util.HashMap<>(t.getParameters()))))
                .toList()
            : Collections.emptyList();
    }
}
