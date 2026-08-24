package ru.pravets.vasyan.action;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.action.actions.*;
import ru.pravets.vasyan.debug.AgentDebugBuffer;
import ru.pravets.vasyan.di.ServiceContainer;
import ru.pravets.vasyan.di.SimpleServiceContainer;
import ru.pravets.vasyan.event.EventBus;
import ru.pravets.vasyan.event.SimpleEventBus;
import ru.pravets.vasyan.execution.*;
import ru.pravets.vasyan.llm.PlanRecord;
import ru.pravets.vasyan.llm.ResponseParser;
import ru.pravets.vasyan.llm.TaskPlanner;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.plugin.ActionRegistry;
import ru.pravets.vasyan.plugin.PluginManager;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Executes actions for a Vasyan entity using the plugin-based action system.
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li>Uses ActionRegistry for dynamic action creation (Factory + Registry patterns)</li>
 *   <li>Uses InterceptorChain for cross-cutting concerns (logging, metrics, events)</li>
 *   <li>Uses AgentStateMachine for explicit state management</li>
 *   <li>Falls back to legacy switch statement if registry lookup fails</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class ActionExecutor {
    private final VasyanEntity vasyan;
    private TaskPlanner taskPlanner;  // Lazy-initialized to avoid loading dependencies on entity creation
    private final Queue<Task> taskQueue;

    private BaseAction currentAction;
    private String currentGoal;
    private int ticksSinceLastAction;
    private BaseAction idleFollowAction;  // Follow player when idle
    /** When true the Vasyan stays in place (stay/stop command) until the next command. */
    private volatile boolean staying = false;

    // NEW: Async planning support (non-blocking LLM calls)
    private final Object planningLock = new Object();
    private Future<ResponseParser.ParsedResponse> planningFuture;
    private boolean isPlanning = false;
    private String pendingCommand;  // Store command while planning
    private int planningStartTick = -1;
    private static final int PLANNING_CHECK_INTERVAL = 20; // once per second

    // NEW: Monotonic request ID so each planning attempt owns its lifecycle atomically
    private long planningRequestSequence = 0L;
    private long activePlanningRequestId = 0L;

    // NEW: Last planning snapshot captured from a completed future (used when the
    // TaskPlanner has not been initialized, e.g. in unit tests that inject futures).
    private volatile PlanRecord lastPlanRecord;

    // NEW: Plugin architecture components
    private final ActionContext actionContext;
    private final InterceptorChain interceptorChain;
    private final AgentStateMachine stateMachine;
    private final EventBus eventBus;

    public ActionExecutor(VasyanEntity vasyan) {
        this.vasyan = vasyan;
        this.taskPlanner = null;  // Will be initialized when first needed
        this.taskQueue = new LinkedList<>();
        this.ticksSinceLastAction = 0;
        this.idleFollowAction = null;
        this.planningFuture = null;
        this.pendingCommand = null;

        // Initialize plugin architecture components
        this.eventBus = new SimpleEventBus();
        this.stateMachine = new AgentStateMachine(eventBus, vasyan.getVasyanName());
        this.interceptorChain = new InterceptorChain();

        // Setup interceptors
        interceptorChain.addInterceptor(new LoggingInterceptor());
        interceptorChain.addInterceptor(new MetricsInterceptor());
        interceptorChain.addInterceptor(new EventPublishingInterceptor(eventBus, vasyan.getVasyanName()));

        // Build action context
        ServiceContainer container = new SimpleServiceContainer();
        this.actionContext = ActionContext.builder()
            .serviceContainer(container)
            .eventBus(eventBus)
            .stateMachine(stateMachine)
            .interceptorChain(interceptorChain)
            .build();

        VasyanMod.LOGGER.debug("ActionExecutor initialized with plugin architecture for Vasyan '{}'",
            vasyan.getVasyanName());
    }
    
    private TaskPlanner getTaskPlanner() {
        if (taskPlanner == null) {
            VasyanMod.LOGGER.info("Initializing TaskPlanner for Vasyan '{}'", vasyan.getVasyanName());
            taskPlanner = new TaskPlanner();
        }
        return taskPlanner;
    }

    private TaskPlanner getTaskPlannerIfInitialized() {
        return taskPlanner;
    }

    /**
     * Returns the planning snapshot of the last completed planning round.
     *
     * <p>For real LLM planning the record comes from {@link TaskPlanner}, which
     * captures the full prompts, raw response and metadata. When planning was
     * driven by an injected future without an initialized {@code TaskPlanner},
     * a partial snapshot built from the parsed response is returned instead.</p>
     *
     * @return the last plan record, or {@code null} if no planning has completed
     */
    public PlanRecord getLastPlanRecord() {
        TaskPlanner planner = getTaskPlannerIfInitialized();
        return planner != null ? planner.getLastPlanRecord() : lastPlanRecord;
    }

    /**
     * Processes a natural language command using ASYNC non-blocking LLM calls.
     *
     * <p>This method returns immediately and does NOT block the game thread.
     * The LLM response is processed in tick() when the CompletableFuture completes.</p>
     *
     * <p><b>Non-blocking flow:</b></p>
     * <ol>
     *   <li>User sends command</li>
     *   <li>This method starts async LLM call, returns immediately</li>
     *   <li>Game continues running normally (no freeze!)</li>
     *   <li>tick() checks if planning is done</li>
     *   <li>When done, tasks are queued and execution begins</li>
     * </ol>
     *
     * @param command The natural language command from the user
     */
    public void processNaturalLanguageCommand(String command) {
        VasyanMod.LOGGER.info("Vasyan '{}' processing command (async): {}", vasyan.getVasyanName(), command);

        long requestId;
        synchronized (planningLock) {
            // If already planning, ignore new commands
            if (isPlanning) {
                VasyanMod.LOGGER.warn("Vasyan '{}' is already planning, ignoring command: {}", vasyan.getVasyanName(), command);
                sendToGUI(vasyan.getVasyanName(), "Hold on, I'm still thinking about the previous command...");
                return;
            }

            // Reserve the planning request atomically with the state check.
            this.pendingCommand = command;
            this.isPlanning = true;
            this.planningStartTick = this.ticksSinceLastAction;
            requestId = ++planningRequestSequence;
            this.activePlanningRequestId = requestId;
        }

        // A new command wakes the Vasyan up from "stay in place".
        // Mutate on the server thread: tick() reads this flag on the game
        // thread and processNaturalLanguageCommand runs on a worker thread.
        var server = vasyan.level().getServer();
        if (server != null && !server.isSameThread()) {
            server.execute(() -> staying = false);
        } else {
            staying = false;
        }

        // Cancel any current actions
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        // Send immediate feedback to user
        sendToGUI(vasyan.getVasyanName(), "Thinking...");

        // Start async LLM call outside the lock.  The future is published only
        // if the reserved request is still active; a stop/stay that ran in the
        // meantime invalidated it and we must abort the now-stale request.
        CompletableFuture<ResponseParser.ParsedResponse> future;
        try {
            future = getTaskPlanner().planTasksAsync(vasyan, command);
        } catch (NoClassDefFoundError e) {
            VasyanMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(vasyan.getVasyanName(), "Sorry, I'm having trouble with my AI systems!");
            synchronized (planningLock) {
                resetPlanningStateLocked();
                activePlanningRequestId = ++planningRequestSequence;
            }
            return;
        } catch (Exception e) {
            VasyanMod.LOGGER.error("Error starting async planning", e);
            sendToGUI(vasyan.getVasyanName(), "Oops, something went wrong!");
            synchronized (planningLock) {
                resetPlanningStateLocked();
                activePlanningRequestId = ++planningRequestSequence;
            }
            return;
        }

        synchronized (planningLock) {
            if (activePlanningRequestId == requestId) {
                this.planningFuture = future;
                VasyanMod.LOGGER.info("Vasyan '{}' started async planning for: {}", vasyan.getVasyanName(), command);
            } else {
                // Request was cancelled between reservation and publication.
                future.cancel(true);
                VasyanMod.LOGGER.debug("Vasyan '{}' discarded planning future for cancelled request {}",
                    vasyan.getVasyanName(), requestId);
            }
        }
    }

    /**
     * Legacy synchronous command processing (blocking).
     *
     * <p><b>Warning:</b> This method blocks the game thread for 30-60 seconds during LLM calls.
     * Use {@link #processNaturalLanguageCommand(String)} instead for non-blocking execution.</p>
     *
     * @param command The natural language command
     * @deprecated Use {@link #processNaturalLanguageCommand(String)} instead
     */
    @Deprecated
    public void processNaturalLanguageCommandSync(String command) {
        VasyanMod.LOGGER.info("Vasyan '{}' processing command (SYNC - blocking!): {}", vasyan.getVasyanName(), command);

        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        try {
            // BLOCKING CALL - freezes game for 30-60 seconds!
            ResponseParser.ParsedResponse response = getTaskPlanner().planTasks(vasyan, command);

            if (response == null) {
                sendToGUI(vasyan.getVasyanName(), "I couldn't understand that command.");
                return;
            }

            currentGoal = response.getPlan();
            vasyan.getMemory().setCurrentGoal(currentGoal);

            taskQueue.clear();
            taskQueue.addAll(response.getTasks());

            if (VasyanConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(vasyan.getVasyanName(), "Okay! " + currentGoal);
            }
        } catch (NoClassDefFoundError e) {
            VasyanMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(vasyan.getVasyanName(), "Sorry, I'm having trouble with my AI systems!");
        }

        VasyanMod.LOGGER.info("Vasyan '{}' queued {} tasks", vasyan.getVasyanName(), taskQueue.size());
    }
    
    /**
     * Send a message to the GUI pane (client-side only, no chat spam)
     */
    private void sendToGUI(String vasyanName, String message) {
        if (vasyan.level().isClientSide) {
            ru.pravets.vasyan.client.VasyanGUI.addVasyanMessage(vasyanName, message);
        }
    }

    public void tick() {
        ticksSinceLastAction++;

        // Check if async planning is complete (non-blocking check!)
        Future<ResponseParser.ParsedResponse> completedFuture = null;
        long expectedId = 0L;
        synchronized (planningLock) {
            if (isPlanning && planningFuture != null && planningFuture.isDone()) {
                completedFuture = planningFuture;
                expectedId = activePlanningRequestId;
            }
        }

        if (completedFuture != null) {
            ResponseParser.ParsedResponse response = null;
            Exception planningError = null;
            try {
                response = completedFuture.get();
            } catch (java.util.concurrent.CancellationException e) {
                planningError = e;
            } catch (java.util.concurrent.ExecutionException e) {
                planningError = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                planningError = e;
            } catch (Exception e) {
                planningError = e;
            }

            synchronized (planningLock) {
                if (expectedId != activePlanningRequestId) {
                    // A stop/start race happened while we were waiting for the future:
                    // this result belongs to an older request, so discard it silently.
                    VasyanMod.LOGGER.debug("Vasyan '{}' discarding stale planning result for request {}",
                        vasyan.getVasyanName(), expectedId);
                    return;
                }

                if (planningError instanceof java.util.concurrent.CancellationException) {
                    VasyanMod.LOGGER.info("Vasyan '{}' planning was cancelled", vasyan.getVasyanName());
                    sendToGUI(vasyan.getVasyanName(), "Planning cancelled.");
                } else if (planningError != null) {
                    VasyanMod.LOGGER.error("Vasyan '{}' failed to get planning result", vasyan.getVasyanName(), planningError);
                    sendToGUI(vasyan.getVasyanName(), "Oops, something went wrong while planning!");
                } else if (response != null) {
                    currentGoal = response.getPlan();
                    vasyan.getMemory().setCurrentGoal(currentGoal);

                    taskQueue.clear();
                    taskQueue.addAll(response.getTasks());

                    if (taskPlanner == null) {
                        lastPlanRecord = new PlanRecord(
                            pendingCommand,
                            null,
                            null,
                            null,
                            response.getReasoning(),
                            response.getPlan(),
                            response.getTasks(),
                            0L,
                            null,
                            false
                        );
                    }

                    AgentDebugBuffer.log(vasyan.getVasyanName(), "PLAN",
                        "goal=\"" + truncate(currentGoal, 200) + "\", queued tasks: " + response.getTasks().size());

                    if (VasyanConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(vasyan.getVasyanName(), "Okay! " + currentGoal);
                    }

                    VasyanMod.LOGGER.info("Vasyan '{}' async planning complete: {} tasks queued",
                        vasyan.getVasyanName(), taskQueue.size());
                } else {
                    sendToGUI(vasyan.getVasyanName(), "I couldn't understand that command.");
                    AgentDebugBuffer.log(vasyan.getVasyanName(), "PLAN", "planning returned null (see LLM/PARSE events)");
                    VasyanMod.LOGGER.warn("Vasyan '{}' async planning returned null response", vasyan.getVasyanName());
                }

                resetPlanningStateLocked();
            }
        }

        synchronized (planningLock) {
            if (isPlanning && planningStartTick >= 0 &&
                (ticksSinceLastAction - planningStartTick) % PLANNING_CHECK_INTERVAL == 0 &&
                (ticksSinceLastAction - planningStartTick) / PLANNING_CHECK_INTERVAL >= 1) {

                int elapsedSeconds = (ticksSinceLastAction - planningStartTick) / 20;
                if (elapsedSeconds >= VasyanConfig.PLANNING_TIMEOUT_SECONDS.get()) {
                    VasyanMod.LOGGER.warn("Vasyan '{}' planning timed out after {}s", vasyan.getVasyanName(), elapsedSeconds);
                    AgentDebugBuffer.log(vasyan.getVasyanName(), "PLAN", "planning timed out after " + elapsedSeconds + "s");

                    if (planningFuture != null) {
                        planningFuture.cancel(true);
                    }

                    sendToGUI(vasyan.getVasyanName(), "LLM planning timed out — please try again.");
                    resetPlanningStateLocked();
                    activePlanningRequestId = ++planningRequestSequence;
                }
            }
        }

        if (currentAction != null) {
            if (currentAction.isComplete()) {
                ActionResult result = currentAction.getResult();
                VasyanMod.LOGGER.info("Vasyan '{}' - Action completed: {} (Success: {})", 
                    vasyan.getVasyanName(), result.getMessage(), result.isSuccess());
                AgentDebugBuffer.log(vasyan.getVasyanName(), result.isSuccess() ? "ACTION_DONE" : "ACTION_FAIL",
                    currentAction.getClass().getSimpleName() + " -> " + truncate(result.getMessage(), 200));
                
                vasyan.getMemory().addAction(currentAction.getDescription());
                
                if (!result.isSuccess() && result.requiresReplanning()) {
                    // Action failed, need to replan
                    if (VasyanConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(vasyan.getVasyanName(), "Problem: " + result.getMessage());
                    }
                }
                
                currentAction = null;
            } else {
                if (ticksSinceLastAction % 100 == 0) {
                    VasyanMod.LOGGER.info("Vasyan '{}' - Ticking action: {}", 
                        vasyan.getVasyanName(), currentAction.getDescription());
                }
                try {
                    currentAction.tick();
                } catch (Exception e) {
                    // An action crash must never leave the Vasyan silently
                    // standing: report it as an honest failure.
                    VasyanMod.LOGGER.error("Vasyan '{}' action '{}' crashed",
                        vasyan.getVasyanName(), currentAction.getClass().getSimpleName(), e);
                    AgentDebugBuffer.log(vasyan.getVasyanName(), "ACTION_FAIL",
                        currentAction.getClass().getSimpleName() + " crashed: " + e);
                    currentAction.cancel();
                    currentAction = null;
                }
                return;
            }
        }

        if (ticksSinceLastAction >= VasyanConfig.ACTION_TICK_DELAY.get()) {
            if (!taskQueue.isEmpty()) {
                Task nextTask = taskQueue.poll();
                executeTask(nextTask);
                ticksSinceLastAction = 0;
                return;
            }
        }
        
        // When completely idle (no tasks, no goal), follow nearest player
        // (unless told to stay in place)
        if (taskQueue.isEmpty() && currentAction == null && currentGoal == null && !staying) {
            if (idleFollowAction == null) {
                idleFollowAction = new IdleFollowAction(vasyan);
                idleFollowAction.start();
            } else if (idleFollowAction.isComplete()) {
                // Restart idle following if it stopped
                idleFollowAction = new IdleFollowAction(vasyan);
                idleFollowAction.start();
            } else {
                // Continue idle following
                idleFollowAction.tick();
            }
        } else if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
    }

    private void executeTask(Task task) {
        VasyanMod.LOGGER.info("Vasyan '{}' executing task: {} (action type: {})", 
            vasyan.getVasyanName(), task, task.getAction());
        
        currentAction = createAction(task);
        
        if (currentAction == null) {
            VasyanMod.LOGGER.error("FAILED to create action for task: {}", task);
            AgentDebugBuffer.log(vasyan.getVasyanName(), "NO_ACTION",
                "no factory for action '" + task.getAction() + "', params=" + task.getParameters());
            return;
        }

        VasyanMod.LOGGER.info("Created action: {} - starting now...", currentAction.getClass().getSimpleName());
        currentAction.start();
        AgentDebugBuffer.log(vasyan.getVasyanName(), "ACTION_START",
            currentAction.getClass().getSimpleName() + " " + task.getAction() + " " + task.getParameters());
        VasyanMod.LOGGER.info("Action started! Is complete: {}", currentAction.isComplete());
    }

    /**
     * Creates an action using the plugin registry with legacy fallback.
     *
     * <p>First attempts to create the action via ActionRegistry (plugin system).
     * If the registry doesn't have the action or creation fails, falls back
     * to the legacy switch statement for backward compatibility.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown action type
     */
    private BaseAction createAction(Task task) {
        String actionType = task.getAction();

        // Try registry-based creation first (plugin architecture)
        ActionRegistry registry = ActionRegistry.getInstance();
        if (registry.hasAction(actionType)) {
            BaseAction action = registry.createAction(actionType, vasyan, task, actionContext);
            if (action != null) {
                VasyanMod.LOGGER.debug("Created action '{}' via registry (plugin: {})",
                    actionType, registry.getPluginForAction(actionType));
                return action;
            }
        }

        // Fallback to legacy switch statement for backward compatibility
        VasyanMod.LOGGER.debug("Using legacy fallback for action: {}", actionType);
        return createActionLegacy(task);
    }

    /**
     * Legacy action creation using switch statement.
     *
     * <p>Kept for backward compatibility during migration to plugin system.
     * Will be removed in a future version once all actions are registered
     * via plugins.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown
     * @deprecated Use ActionRegistry instead
     */
    @Deprecated
    private BaseAction createActionLegacy(Task task) {
        return switch (task.getAction()) {
            case "pathfind" -> new PathfindAction(vasyan, task);
            case "mine" -> new MineBlockAction(vasyan, task);
            case "place" -> new PlaceBlockAction(vasyan, task);
            case "craft" -> new CraftItemAction(vasyan, task);
            case "attack" -> new CombatAction(vasyan, task);
            case "follow" -> new FollowPlayerAction(vasyan, task);
            case "teleport" -> new TeleportAction(vasyan, task);
            case "stay" -> new StayAction(vasyan, task);
            case "gather" -> new GatherResourceAction(vasyan, task);
            case "build" -> new BuildStructureAction(vasyan, task);
            default -> {
                VasyanMod.LOGGER.warn("Unknown action type: {}", task.getAction());
                yield null;
            }
        };
    }

    /**
     * Removes all pending tasks without cancelling the currently running
     * action. Used by "stay": after the current task, the Vasyan must not
     * continue executing a multi-task plan.
     */
    public void clearTaskQueue() {
        taskQueue.clear();
    }

    public void stopCurrentAction() {
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }
        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        taskQueue.clear();
        currentGoal = null;
        // Reset state machine
        stateMachine.reset();
        cancelPlanning();
    }

    /**
     * Cancels an in-progress async LLM planning future and clears all
     * planning-related state. Called from {@link #stopCurrentAction()} so
     * stop/stay commands abort planning as well as execution.
     */
    private void cancelPlanning() {
        synchronized (planningLock) {
            if (isPlanning || planningFuture != null) {
                if (planningFuture != null) {
                    planningFuture.cancel(true);
                }
                VasyanMod.LOGGER.info("Vasyan '{}' planning cancelled by stop", vasyan.getVasyanName());
                AgentDebugBuffer.log(vasyan.getVasyanName(), "PLAN", "planning cancelled by stop command");
                if (vasyan.level().isClientSide()) {
                    sendToGUI(vasyan.getVasyanName(), "Planning cancelled.");
                }
                resetPlanningStateLocked();
                activePlanningRequestId = ++planningRequestSequence;
            }
        }
    }

    /**
     * Clears all planning-related state under planningLock. Does not touch
     * the request-id generator; callers must bump activePlanningRequestId
     * separately when invalidating the current request.
     */
    private void resetPlanningStateLocked() {
        isPlanning = false;
        planningFuture = null;
        pendingCommand = null;
        planningStartTick = -1;
    }

    /**
     * Puts the Vasyan in (or out of) "stay in place" mode. While staying,
     * no idle-follow is started and the Vasyan does not move; any new
     * command wakes it up automatically.
     */
    public void setStaying(boolean staying) {
        this.staying = staying;
        if (staying) {
            vasyan.getNavigation().stop();
        }
    }

    public boolean isStaying() {
        return staying;
    }

    public boolean isExecuting() {
        return currentAction != null || !taskQueue.isEmpty();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Returns the event bus for subscribing to action events.
     *
     * @return EventBus instance
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * Returns the agent state machine.
     *
     * @return AgentStateMachine instance
     */
    public AgentStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Returns the interceptor chain for adding custom interceptors.
     *
     * @return InterceptorChain instance
     */
    public InterceptorChain getInterceptorChain() {
        return interceptorChain;
    }

    /**
     * Returns the action context.
     *
     * @return ActionContext instance
     */
    public ActionContext getActionContext() {
        return actionContext;
    }

    /**
     * Checks if the agent is currently planning (async LLM call in progress).
     *
     * @return true if planning
     */
    public boolean isPlanning() {
        synchronized (planningLock) {
            return isPlanning;
        }
    }

    /**
     * Test-only hook that injects a planning future without starting a real LLM call.
     * Package-private so unit tests in the same package can set up a stuck-planning scenario.
     */
    void setPlanningFutureForTest(Future<ResponseParser.ParsedResponse> future, String command) {
        synchronized (planningLock) {
            this.pendingCommand = command;
            this.isPlanning = true;
            this.planningFuture = future;
            this.planningStartTick = this.ticksSinceLastAction;
            this.activePlanningRequestId = ++planningRequestSequence;
        }
    }

    /**
     * Number of tasks waiting in the queue.
     */
    public int getQueuedTaskCount() {
        return taskQueue.size();
    }

    /**
     * Description of the currently running action, or null if idle.
     */
    public String getCurrentActionDescription() {
        return currentAction != null ? currentAction.getDescription() : null;
    }

    /**
     * One-line summary of the agent state, used by /vasyan debug.
     */
    public String getStateSummary() {
        String summary;
        synchronized (planningLock) {
            if (isPlanning) {
                summary = "planning (" + (pendingCommand != null ? truncate(pendingCommand, 50) : "?") + ")";
            } else {
                summary = null;
            }
        }
        if (summary != null) {
            return summary;
        }
        if (currentAction != null) {
            return "executing: " + truncate(currentAction.getDescription(), 60)
                + " (queue: " + taskQueue.size() + ")";
        }
        if (!taskQueue.isEmpty()) {
            return "waiting, " + taskQueue.size() + " tasks queued";
        }
        return "idle (following player)";
    }

    private static String truncate(String str, int maxLength) {
        if (str == null) return "[null]";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }
}
