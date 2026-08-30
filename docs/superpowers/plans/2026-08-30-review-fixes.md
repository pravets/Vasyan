# Phase 0.6 P1 — Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Исправить замечания код-ревью ветки `feat/phase06-p1-pathfinding-hygiene`: 2 critical-бага (фантомный Y станций, потерянный refresh в GlobalResourceMemory), 10 major и пачка minor/гигиены — до повторного ревью и мержа.

**Architecture:** Точечные фиксы поверх существующей архитектуры (pure-классы `navigation/` + thin glue `VasyanPathing`). Никаких новых подсистем; один новый goal-примитив (`horizontalNear`), одна перегрузка бюджета от игровых тиков, переименование конфига dig-лимита.

**Tech Stack:** Java 17, Minecraft 1.20.1 Forge 47.2.0, JUnit 5 (+ `McTestBootstrap`), RCON behavior-tests.

**Spec:** результаты код-ревью ветки (сводка в PR/сессии от 2026-08-30) + `docs/superpowers/plans/2026-08-24-phase06-p1-pathfinding-hygiene.md` (исходный план, чьи решения здесь корректируются).

## Global Constraints

- Branch from `master` only; one PR = one task; author `Iosif Pravets <i@pravets.ru>`; conventional commits.
- Java 17, 4-space indent, max line length 120, JavaDoc на публичных API.
- Локально только `nice -n19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`. Полные тесты — GitHub CI (`gh workflow run Build` / `behavior-tests --ref feat/phase06-p1-pathfinding-hygiene`).
- RED-фаза TDD допускается через CI (`gh run view --log-failed`).
- Vanilla registry в unit-тестах: только через `McTestBootstrap.bootstrap()`.
- Бот ходит, не летает: телепорт — только явная команда или последний fallback `PathMonitor` (hop-teleport по умолчанию выключен, НЕ включаем).
- Не ломать существующие тесты: базовая линия = число тестов последнего зелёного Build.

## Решения, принятые на ревью (обязательны к исполнению)

1. **Вода → вариант A:** телепорт из воды НЕ возвращаем. Give-up монитора, когда бот в воде, помечает цель недостижимой только **локально** (per-action), в `GlobalResourceMemory` не пишется.
2. **Dig-лимит = глубина туннеля:** один вызов `digThrough` = 1 блок глубины (коридор «ноги+голова» — это нормально). Код не меняем; конфиг `digThroughMaxBlocks` переименовывается в **`digThroughMaxDepth`**, документация и тесты приводятся к семантике «глубина туннеля в блоках».

---

## File Structure

Изменяемые (новых файлов почти нет):

```
src/main/java/ru/pravets/vasyan/
├── navigation/
│   ├── VasyanGoal.java              (+ фабрика horizontalNear)
│   ├── PathBudgets.java             (fix валидации nanoTime; + фабрика от тиков)
│   ├── PathMonitor.java             (ASCEND-gate, retarget, javadoc, константы)
│   ├── VasyanPathing.java           (replan после teleport, логи, дедупликация, dismantle-фикс)
│   └── VerticalTraversalPlanner.java (long в horizontalDistanceSqr — по желанию в hygiene-задаче)
├── action/actions/
│   ├── GatherResourceAction.java    (horizontalNear для станций; тиковый бюджет; вода-локальный skip; leaf-лимит; мелочи)
│   ├── CombatAction.java            (retarget вместо пересоздания монитора; удалить мёртвый workaround)
│   ├── PathfindAction.java          (тиковый бюджет)
│   └── FollowPlayerAction.java      (тиковый бюджет — если применимо)
├── memory/GlobalResourceMemory.java (refresh-фикс, dimension-ключ, clear(), LinkedHashSet)
├── memory/VisionScanner.java        (targets-фильтр в scanTargets, hasChunkAt, импорты)
├── config/VasyanConfig.java         (stack trace; digThroughMaxDepth; gather.leafDigMaxBlocks)
├── chat/ChatCommandParser.java      (валидация диапазона координат)
├── llm/resilience/LLMFallbackHandler.java + ProviderChainClient.java (минор)
└── action/ActionExecutor.java       (setCurrentGoal в executeDirectTask)
src/test/java/ru/pravets/vasyan/... (соответствующие тесты)
scripts/behavior/behavior_test.py    (чистка сценариев C/G, офсеты)
.github/workflows/build.yml          (убрать pr-39)
.github/workflows/behavior-tests.yml (timeout 45, комментарий)
build.gradle                         (options.encoding = 'UTF-8')
```

---

## Этап 1. Critical-баги

### Task 1: Горизонтальная цель для станций (fix C1)

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/navigation/VasyanGoal.java`
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/GatherResourceAction.java:453-455`
- Test: `src/test/java/ru/pravets/vasyan/navigation/VasyanGoalTest.java`

**Контекст проблемы:** `ResourceSearchPlanner.stationFor` ставит станции на `y = origin.y + STATION_HEIGHT_OFFSET` — фантомная высота, не рельеф. `GoalNear` (3D Chebyshev, range 3) на склоне >3 недостижим → ложный give-up + отравление глобальной памяти.

**Interfaces:**
- Produces:
```java
/** Цель «горизонтально в радиусе range (Chebyshev по XZ), высота игнорируется». Для look-out станций. */
public static VasyanGoal horizontalNear(BlockPos target, int rangeBlocks);
```
Реализация — новый record `GoalHorizontalNear(BlockPos target, int range)` в `navigation/`, `hasReached`: `max(|dx|,|dz|) <= range`.

- [ ] **Step 1: failing test** в `VasyanGoalTest`: `horizontalNear(pos, 3)` — `hasReached` = true при dy=±10 (тот же X/Z); true при dx=3, dz=3; false при dx=4; IAE при range < 0.
- [ ] **Step 2: RED via CI** (`gh workflow run Build`, verify FAIL).
- [ ] **Step 3: реализация** — record + фабрика; в `GatherResourceAction` станции (ветка `else` на строке 455) получают `VasyanGoal.horizontalNear(routeTarget, STATION_GOAL_RANGE)`; mine-цели остаются `near(routeTarget, 1)`. Обновить комментарий на строках 445-449.
- [ ] **Step 4: GREEN** — CI Build ✅.
- [ ] **Step 5:** `git commit -m "fix(gather): station arrival uses horizontal goal (phantom Y made slopes unreachable)"`

### Task 2: GlobalResourceMemory — refresh, dimension-ключ, clear()

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/memory/GlobalResourceMemory.java:103-113`
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/GatherResourceAction.java` (все вызовы `remember*/is*/prune` — пробросить dimension)
- Test: `src/test/java/ru/pravets/vasyan/memory/GlobalResourceMemoryTest.java`

**Interfaces:**
- Produces (новые сигнатуры, вызывающие обновляются):
```java
public static void rememberEmptyStation(String resource, ResourceKey<Level> dimension, BlockPos station, long currentTick);
public static void rememberUnreachable(String resource, ResourceKey<Level> dimension, BlockPos center, long currentTick);
public static boolean isEmptyStation(String resource, ResourceKey<Level> dimension, BlockPos station, long currentTick, int radius);
public static boolean isUnreachable(String resource, ResourceKey<Level> dimension, BlockPos pos, long currentTick, int radius);
public static void prune(long currentTick, long ttlTicks);
/** Полная очистка (остановка сервера, тесты). */
public static void clear();
```
Внутренний ключ: `resource + "|" + dimension.location()`.

- [ ] **Step 1: failing tests** в `GlobalResourceMemoryTest`:
  - refresh: `rememberEmptyStation("coal", OW, p, 0)` → `rememberUnreachable("coal", OW, p2, 5000)` → `prune(6000, 2000)` → `isEmptyStation("coal", OW, p, 6000, 16)` == **true** (запись жива — TTL от последнего доступа);
  - изоляция измерений: запись в `Level.OVERWORLD` не видна из `Level.NETHER`;
  - изоляция ресурсов (переименовать существующий лживый тест): `"iron"` не видит кластер `"coal"`;
  - `clear()` сбрасывает всё; `@BeforeEach` → `clear()` (устраняет связанность тестов статическим состоянием).
- [ ] **Step 2: RED via CI.**
- [ ] **Step 3: реализация**:
  - `getOrCreate`: `MEMORY.put(key, refreshed)` на каждом обращении (убрать врущий комментарий);
  - сеты → `LinkedHashSet` (removeOldest реально удаляет старые; синхронизация — оставить `synchronizedSet` + добавить `synchronized (set)` вокруг итераций в `isEmptyStation/isUnreachable/removeOldest`, или задокументировать «server thread only» в класс-доке и убрать concurrent-обёртки — выбрать первое, дёшево);
  - ключ карты `resource + "|" + dimension.location()`;
  - `clear()`.
- [ ] **Step 4: GREEN** + полный Build ✅.
- [ ] **Step 5:** `git commit -m "fix(memory): refresh lastUsedTick on access; key zones by dimension"`

### Task 3: PathBudgets — отрицательный nanoTime

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/navigation/PathBudgets.java:41-51`
- Test: `src/test/java/ru/pravets/vasyan/navigation/PathBudgetsTest.java`

- [ ] **Step 1: failing test**: `PathBudgets.start(-9_000_000_000L, 2000, 10, 64)` не кидает; `thinkExpired` корректно до/после дедлайна при отрицательном старте.
- [ ] **Step 2: RED via CI.**
- [ ] **Step 3:** убрать проверку `deadlineNano > 0` из компактного конструктора (валидация таймаутов `> 0` остаётся); сравнения `nowNano >= deadlineNano` корректны при любом знаке (вычитание long переполняется безопасно — оставить как есть).
- [ ] **Step 4: GREEN.**
- [ ] **Step 5:** `git commit -m "fix(navigation): allow negative nanoTime origin in PathBudgets"`

---

## Этап 2. Major-баги логики

### Task 4: Think-бюджет в игровых тиках (fix M2)

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/navigation/PathBudgets.java`
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/PathfindAction.java:63,81`, `CombatAction.java:115`, `GatherResourceAction.java:456-476`
- Test: `src/test/java/ru/pravets/vasyan/navigation/PathBudgetsTest.java`

**Interfaces:**
- Produces:
```java
/** Бюджет, где think-дедлайн измеряется в игровых тиках (gameTime), а не wall-clock. */
public static PathBudgets startInTicks(long startTick, long thinkTimeoutMs, long tickTimeoutMs, int searchRadius);
public boolean thinkExpiredTicks(long currentTick); // currentTick - startTick > thinkTimeoutMs / 50
```
Record пополняется полем `thinkDeadlineTick` (или отдельный record `TickThinkBudget` — выбрать менее интрузивное; сигнатуры выше обязательны).

- [ ] **Step 1: failing test**: бюджет 2000 мс = 40 тиков; `thinkExpiredTicks(start+41)` == true, `thinkExpiredTicks(start+40)` == false; wall-clock `nanoTime` больше не влияет на moved-проверку.
- [ ] **Step 2: RED via CI.**
- [ ] **Step 3:** экшены стартуют бюджет с `vasyan.level().getGameTime()` и проверяют `thinkExpiredTicks(gameTime)` вместо `thinkExpired(nanoTime)` в moved-ветках. `nextTick` остаётся нано-based (per-tick дедлайн планирования — не трогаем).
- [ ] **Step 4: GREEN** + полный Build ✅.
- [ ] **Step 5:** `git commit -m "fix(navigation): measure think budget in game ticks, not wall clock"`

### Task 5: Combat — retarget вместо пересоздания монитора (fix M3)

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/navigation/PathMonitor.java` (+ `retarget`)
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/CombatAction.java:102-105`
- Test: `src/test/java/ru/pravets/vasyan/navigation/PathMonitorTest.java`

**Interfaces:**
- Produces:
```java
/** Перенацелить монитор на новый anchor без сброса recovery-бюджетов (dig/scaffold/teleport). */
public void retarget(BlockPos newAnchor);
```
Сбрасываются: stall-окно, replan-счётчики (это новый маршрут); сохраняются: `digThroughUsed`, one-shot teleport-флаг, `placedSupports`.

- [ ] **Step 1: failing test**: монитор израсходовал 2 dig-глубины → `retarget(newPos)` → доступно только `maxDig - 2`; teleport-флаг переживает retarget.
- [ ] **Step 2: RED via CI.**
- [ ] **Step 3:** `CombatAction.startRoute` при смене `blockPosition()` цели вызывает `monitor.retarget(target.blockPosition())` + `navigation.moveTo` вместо `new PathMonitor(...)`. Ре-роут только если цель ушла > 4 блоков от routed-точки (порог — именованная константа `RETARGET_DISTANCE_SQ = 16`).
- [ ] **Step 4: GREEN.**
- [ ] **Step 5:** `git commit -m "fix(combat): keep recovery budgets across target re-routes"`

### Task 6: Вода — локальный skip без отравления глобальной памяти (решение A, fix M4)

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/GatherResourceAction.java` (`skipCurrentRouteTarget` и места записи в `GlobalResourceMemory`)
- Test: `src/test/java/ru/pravets/vasyan/action/actions/GatherResourceActionTest.java`

**Interfaces:**
- Produces:
```java
/** Истинно, если give-up произошёл, пока бот был в воде: цель помечается только локально. */
static boolean shouldKeepLocalOnly(boolean monitorGaveUp, boolean botInWater); // pure helper
```
(`shouldKeepLocalOnly(true, true) == true`; вода = `vasyan.isInWater()` в точке вызова.)

- [ ] **Step 1: failing test** хелпера (3 кейса: gaveUp+water → true; gaveUp+суша → false; не gaveUp → false).
- [ ] **Step 2: RED via CI.**
- [ ] **Step 3:** при `shouldKeepLocalOnly(...) == true` — только локальный `unreachableTargets`/blacklist, без `GlobalResourceMemory.rememberUnreachable/rememberEmptyStation`. Класс-док GatherResourceAction: задокументировать сознательный отказ от fish-out телепорта.
- [ ] **Step 4: GREEN.**
- [ ] **Step 5:** `git commit -m "fix(gather): drowned give-up no longer poisons global unreachable memory"`

### Task 7: Листва — отдельный лимит для крон (fix M5)

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/config/VasyanConfig.java` (`[gather] leafDigMaxDepth = 12, range 0..64`)
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/GatherResourceAction.java` (recovery-политика для tree-маршрутов)
- Modify: `src/main/java/ru/pravets/vasyan/navigation/VasyanPathing.java` (enforce принимает dig-лимит или политика несёт лимит)

**Interfaces:**
- Produces: `VasyanConfig.GATHER_LEAF_DIG_MAX_DEPTH` (IntValue, default 12). RecoveryPolicy остаётся enum'ом; dig-лимит пробрасывается в монитор параметром конструктора (уже есть телескопический конструктор — добавить перегрузку с `maxDigDepth`, default = `NAV_DIG_THROUGH_MAX`).
- Проверить и задокументировать: листва НЕ в dig-blacklist.

- [ ] **Step 1: failing test** в `PathMonitorTest`: монитор с `maxDigDepth=12` выдаёт 12 DIG_THROUGH до эскалации.
- [ ] **Step 2: RED via CI.**
- [ ] **Step 3:** tree-маршруты (`recoveryPolicy() == FULL` и `logTarget`) создают монитор с leaf-лимитом.
- [ ] **Step 4: GREEN.**
- [ ] **Step 5:** `git commit -m "fix(gather): tree routes may dig up to 12 leaves deep (mangrove canopies)"`

### Task 8: VisionScanner — targets-фильтр в scanTargets (fix M2-vision)

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/memory/VisionScanner.java:484,491-509`
- Test: `src/test/java/ru/pravets/vasyan/memory/VisionScannerTest.java`

- [ ] **Step 1: failing test**: `findVisible(vasyan, Set.of(STONE))` с `COAL_ORE` в центральном столбце бота → результат не содержит COAL_ORE.
- [ ] **Step 2: RED via CI.**
- [ ] **Step 3:** в финальном цикле `scanTargets` пропускать записи, чей блок не в `targets` (или передать `targets` в `collectVerticalColumn` — выбрать меньший diff).
- [ ] **Step 4: GREEN.**
- [ ] **Step 5:** `git commit -m "fix(vision): vertical-column scan respects requested target set"`

### Task 9: ASCEND-gate по horizontalRange + мёртвый тест + flowing-water тесты (fix M11/M12/M13)

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/navigation/PathMonitor.java:448-453`
- Test: `src/test/java/ru/pravets/vasyan/navigation/PathMonitorTest.java:72` (+ `@Test`, правка `distantUphillRouteUsesLocalAscendRecovery`)
- Test: `src/test/java/ru/pravets/vasyan/navigation/VerticalTraversalPlannerTest.java` (фикстуры `isFlowingWater=true`: ASCEND в водопад отклоняется; контроль — стоячая вода разрешена)
- Test: `src/test/java/ru/pravets/vasyan/navigation/VasyanGoalTest.java` (`anchor()`: выбор ближайшего под-goal в composite; `GoalCompositeAny` — IAE на пустом, защитное копирование)

- [ ] **Step 1: failing tests**: distant uphill (dy=3, horizontal=100) → НЕ ASCEND (ожидаем GIVE_UP-путь лестницы); включённый `@Test` на stall-окно падает, если off-by-one сломан (он должен проходить — это ре-активация); waterfall-фикстуры; anchor-тесты.
- [ ] **Step 2: RED via CI** (distant-uphill тест падает на старом коде).
- [ ] **Step 3:** добавить `dy > 0 && horizontal > horizontalRange → null` (симметрично DESCEND) в `verticalDecision` с комментарием «дальний подъём — это маршрутная проблема, не recovery».
- [ ] **Step 4: GREEN.**
- [ ] **Step 5:** `git commit -m "fix(navigation): gate ASCEND recovery by horizontalRange; re-enable stall-window test"`

### Task 10: Replan после hop-teleport (fix VasyanPathing)

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/navigation/VasyanPathing.java:668-683`

- [ ] **Step 1:** после успешного `teleportTo` — `vasyan.getNavigation().stop()` + `replan(vasyan, monitor)` (по образцу `digThrough`/`placeScaffold`). Тестовый каркаса нет (world-glue) — покрытие behavior-сценариями; отметить в класс-доке.
- [ ] **Step 2:** локальный `compileJava` ✅; полный Build CI ✅.
- [ ] **Step 3:** `git commit -m "fix(navigation): replan after hop teleport"`

---

## Этап 3. Minor и гигиена

### Task 11: Конфиг и логи

- `VasyanConfig.java:78` — вернуть `error("Failed to quarantine broken vasyan-common.toml", quarantineFailure)` (stack trace).
- `VasyanConfig` — переименовать `digThroughMaxBlocks` → **`digThroughMaxDepth`** (комментарий: «макс. глубина туннеля в блоках»; решение ревью №2); обновить все usages и комментарий в `PathMonitor` (javadoc «every successfully dug block is spent» → «каждый шаг продвижения туннеля расходует 1 единицу глубины; шаг ломает коридор ноги+голова»); тест `digThroughBudgetStopsHorizontalTunnelingAcrossProgressResets` — уточнить имя/комментарии под «глубину».
- `VasyanPathing.java:200-206` — удалить «temporary diagnostics»; `:346,446,499,656,680,709` — WARN → INFO для штатных recovery-операций.
- Комментарии `STT_API_KEY`/`STT_MODEL` (copy-paste) — почистить.
- **Commit:** `chore(config,logging): rename dig limit to tunnel depth, restore stack trace, drop temp diagnostics`

### Task 12: Javadoc и мёртвый код

- `VasyanPathing.java:182` — `{@link RecoveryPolicy#ASCEND_ONLY}` → `VERTICAL_ONLY`; `inLadderRecovery()` javadoc — под реальную семантику («после первого paced navDone-replan'а»); `VasyanGoal.anchor()`, `VerticalTraversalPlanner.nextStep` — `@param`/`@return`.
- Удалить `PathMonitor.onProgress()` (мёртвый) или задокументировать как public API для будущих экшенов — выбрать удаление; `GatherResourceAction.java:347` — мёртвая `center`; `:326-327` — переименовать локальную `logTarget` (затеняет поле); `CombatAction.java:146-153` — удалить избыточный workaround invulnerability (steerTo сохраняет флаг с 1655045), оставить комментарий-ссылку на steerTo.
- Комментарии, врущие о коде: `GatherResourceAction.java:352-356` («сухая земля» — вода проходима), `:371-378` («Do NOT mark a mine target» — mineTarget обнуляется раньше; выровнять код или комментарий: НЕ помечать только-что выкопанный блок пустой станцией — проверить `lastMineTarget != null`), `isExposedForMining` javadoc (логи всегда true), `LLMFallbackHandler.java:70-73` (устаревший комментарий про ProviderChainClient).
- `executeDirectTask` — `vasyan.getMemory().setCurrentGoal(...)` по образцу NL-пути (`ActionExecutor.java:262-263`).
- `ChatCommandParser.parseGoToCommand` — валидация диапазона: Y в пределах world height (параметром или константами 1.20.1: -64..319), |X|,|Z| ≤ 30_000_000; тесты на границы.
- **Commit:** `refactor: drop dead code, fix stale javadoc/comments, align direct-task goal memory`

### Task 13: Магические числа, мелкие фиксы навигации

- Константы: `PathMonitor` — `MAX_PLACED_SUPPORTS = 64` (+ WARN при обрезке `placedSupports`); `VasyanPathing` — `MAX_PREPARE_CHAIN = 4`, `AHEAD_SCAN_CELLS = 2`; убрать fully-qualified `net.minecraft.core.Direction`; дедупликация выбора направления `aheadPosition`/`findDiggableAhead` (общий private helper `facingToward(BlockPos target)`).
- `VerticalTraversalPlanner.horizontalDistanceSqr` → `long`.
- `dismantlePlacedSupports` — хранить `Block` вместе с позой в `placedSupports` (record `PlacedSupport(BlockPos pos, Block block)`); ломать только если `level.getBlockState(pos).is(block)`, иначе WARN «support replaced, skipping».
- `PathMonitor` — задокументировать `placedSupports`/`recordPlacedSupport`; тесты на 64-cap.
- **Commit:** `refactor(navigation): name magic numbers, dismantle only self-placed support blocks`

### Task 14: Vision/LLM/тесты мелочи

- `VisionScanner.findNearbyBlocks` — добавить `level.hasChunkAt` guard (по образцу `collectCandidates`); `hasStandableApproach` — проверка пола под клеткой подхода или переименовать в `hasPassableApproach` (выбрать переименование — меньший риск); вынести `BlockTags.LOGS`-проверку из горячего цикла `scanWorld`; fully-qualified имена (`VisionScanner.java:155,157,197`) → импорты; тесты — то же (`VisionScannerTest:183-184`).
- `LLMFallbackHandler` — паттерны coalQty/woodQty/coal/wood → `static final`; coal/wood-ветки матчат только хвост после «CURRENT REQUEST:» (обрезать промпт перед матчем); тест: «gather 10 iron» + `coal_ore` в ситуации → fallback НЕ coal.
- `ProviderChainClient` — синтезированный fallback: `failureReason = "all providers in the failover chain are unavailable"`.
- `ChatCommandParserTest` — wildcard import → явные.
- **Commit:** `fix(vision,llm): chunk guard for no-LOS scan, prompt-scoped fallback matching`

### Task 15: Behavior-тесты и CI

- `build.yml:5` — убрать `pr-39` из `branches`.
- `behavior-tests.yml:13` — `timeout-minutes: 45`; комментарий «Two server starts» → три.
- `behavior_test.py`:
  - сценарий G: перед запуском `setblock` угля из D (wx+38, y=197) в `stone` — устраняет flaky-пересечение;
  - сценарий C: удалить «resync probe» (`rcon.command("list")`) и цикл «Diagnose the paradox» (строки 580-592);
  - удалить мёртвые ассерты-строки `"DESCEND placed support"`/`"ASCEND placed support"` (643, 661, 678 — реальный лог `placed support {} at {}`) и `"HIDDEN_COAL_INTACT"` (735);
  - `goto()` — удалить неиспользуемые параметры `forbid_teleport`/`forbid_dig`;
  - `wait_for` в G/J (727, 836) — передать anchor-офсеты;
  - ассерт 744 — матчить полный паттерн координат, не голое число;
  - H/I — убрать `sleep(4)`+summon (не нужны) или поллить инвентарь как в D (выбрать удаление);
  - `RCON._drain_empty_packets:66` — фикс перепаковки длины: `struct.pack("<i", 10 + len(body))`;
  - docstring 432 «Three scenarios» → десять (A–J); 436-437 — привести к GoalNear-семантике сценария B.
- **Commit:** `test(behavior,ci): drop pr-39 trigger, raise timeout, deflake scenario G, clean debug leftovers`

### Task 16: build.gradle — кодировка

- `build.gradle` — `tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }` (чинит локальную компиляцию на Windows; проверить, что CI не регрессирует).
- **Commit:** `build: force UTF-8 for javac (Windows local builds)`

---

## Этап 4. Финализация

### Task 17: Прогон и повторное ревью

- [ ] **Step 1:** локальный `compileJava compileTestJava` после каждого коммита (проверено по ходу).
- [ ] **Step 2:** `gh workflow run Build` ✅ — число тестов ≥ базовая линия + новые (ожидается +~15 объявлений `@Test`).
- [ ] **Step 3:** `gh workflow run behavior-tests --ref feat/phase06-p1-pathfinding-hygiene` ✅.
- [ ] **Step 4:** обновить чекбоксы в этом файле; AGENTS.md не меняется (структура/команды прежние).
- [ ] **Step 5:** push ветки, CodeRabbit повторное ревью, мердж по команде пользователя.

## Risks / Open Questions

1. **Task 4 (тиковый бюджет):** FollowPlayerAction пересоздаёт бюджет молча — проверить, что переход на тики не ломает long-lived семантику (там think-бюджет не роняет action).
2. **Task 7 (leaf-лимит):** если окажется, что листва входит в dig-blacklist glue — снять её оттуда только для tree-маршрутов, не глобально.
3. **Task 11 (переименование конфига):** ветка не вмерджена — несовместимость TOML бесплатна; если успеют появиться пользовательские конфиги с `digThroughMaxBlocks`, добавить миграцию-чтение старого ключа. Принято: не добавлять.
4. **Порядок:** Tasks 1–3 независимы между собой; Tasks 4–10 зависят только от своих файлов; этап 3 — после этапов 1–2 (правит те же строки). CI-хирургия (Task 15, `pr-39`) — обязательно до финального push.
