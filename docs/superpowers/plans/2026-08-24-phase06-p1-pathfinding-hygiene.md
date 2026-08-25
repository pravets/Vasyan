# Phase 0.6 P1 — Pathfinding Hygiene Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Навигация Vasyan перестаёт застревать молча: единый `PathMonitor` вместо пяти копий stall-логики, иерархия целей `VasyanGoal`, бюджеты планирования — сервер не фризится, бот доходит туда, куда раньше не доходил.

**Architecture:** Три новых чистых класса (`VasyanGoal`, `PathMonitor`, `PathBudgets`) + один серверный (`VasyanPathNavigator`-обёртка над `AmphibiousPathNavigation`). Вся геометрия/решения — в pure-классах без обращения к world (unit-тесты в plain JUnit); классы, трогающие level, изолированы и покрыты bootstrap-тестами. Экшены (`GatherResourceAction`, `CombatAction`, `FollowPlayerAction`, `PathfindAction`) переключаются на общий монитор, свои копии stall-детекции удаляются.

**Tech Stack:** Java 17, Minecraft 1.20.1 Forge 47.2.0 official mappings, JUnit 5 (+ `McTestBootstrap` для registry), RCON behavior-tests.

**Spec:** ROADMAP.md → «Phase 0.6 → P1 — Гигиена навигации» (строки 59–64) + issue #35.

## Global Constraints

- Branch from `master` only; one PR = one task; author `Iosif Pravets <i@pravets.ru>`; conventional commits.
- Java 17, 4-space indent, max line length 120 (case-списки разбивать), JavaDoc на публичных API.
- Локально только `nice -n19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`. Полные тесты/сборка — GitHub CI (`gh workflow run Build` / `behavior-tests --ref <branch>`).
- RED-фаза TDD допускается через CI (`gh run view --log-failed`) — это считается «увидел тест упавшим».
- Vanilla registry в unit-тестах: только через `McTestBootstrap.bootstrap()` (идемпотентный). `BlockPos`, геометрия, pure-классы — без bootstrap.
- Бот ходит, не летает: `setFlying(false)` перед каждым `moveTo`; телепорт — только явная команда или последний fallback `PathMonitor`.
- Сохранять amphibious navigation (уже в `VasyanEntity.createNavigation`).
- Не ломать существующие тесты: базовая линия = число тестов последнего зелёного Build на master (сверить при старте: `grep -c @Test src/test -r`, сейчас ~199 объявлений; фактический прогон см. в CI).

---

## File Structure

```
src/main/java/ru/pravets/vasyan/navigation/
├── VasyanGoal.java            (pure: цель-условие, hasReached(BlockPos))
├── GoalNear.java              (pure)
├── GoalAdjacent.java          (pure)
├── GoalXZ.java                (pure)
├── GoalY.java                 (pure)
├── GoalCompositeAny.java      (pure)
├── PathBudgets.java           (pure: think/tick/radius лимиты + учёт расхода)
├── PathMonitor.java           (pure: stall/replan/fallback FSM, решения без world)
└── VasyanPathing.java         (server glue: навешивает monitor на navigation, исполняет решения)
```

Изменяемые: `GatherResourceAction`, `CombatAction`, `FollowPlayerAction`, `PathfindAction`, `VasyanConfig`.

---

### Task 1: VasyanGoal hierarchy (pure)

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/navigation/VasyanGoal.java`
- Create: `src/main/java/ru/pravets/vasyan/navigation/GoalNear.java`, `GoalAdjacent.java`, `GoalXZ.java`, `GoalY.java`, `GoalCompositeAny.java`
- Test: `src/test/java/ru/pravets/vasyan/navigation/VasyanGoalTest.java`

**Interfaces:**
- Produces:
```java
public interface VasyanGoal {
    boolean hasReached(BlockPos botPos);
    String describe(); // для логов/debug
}
public static GoalNear near(BlockPos target, int rangeBlocks);
public static GoalAdjacent adjacent(BlockPos block); // сбоку: |dx|+|dz|==1 и тот же y (top НЕ adjacency)
public static GoalXZ xz(int x, int z);              // |dx|<=1 && |dz|<=1, y игнор
public static GoalY y(int y);                       // |dy|<=1
public static GoalCompositeAny any(VasyanGoal... goals);
```

- [ ] **Step 1: failing test** — `VasyanGoalTest`: near(дистанция 3, range 2)=false; near(2,2)=true; adjacent(сбоку)=true; adjacent(top y+1)=**false**; adjacent(диагональ)=false; xz игнорирует высоту; any(X,Y) достигнут когда X достигнут.
- [ ] **Step 2: RED** — локальный compileTestJava не нужен (тест компилится, но падает в CI): `gh workflow run Build`, verify FAIL через `gh run view --log-failed`.
- [ ] **Step 3: реализация** — интерфейс + 5 record-классов с `hasReached` по формулам выше.

**Adjacency semantics (замечание ревью №5):** `GoalAdjacent` = только сбоку (`|dx|+|dz|==1 && dy==0`); позиция НАД блоком adjacency не считается. Согласовано с behavior-тестом `test_adjacent_stand` (Task 8): манхэттен XZ==1 при равной высоте.
- [ ] **Step 4: GREEN** — CI Build ✅.
- [ ] **Step 5:** `git commit -m "feat(navigation): VasyanGoal hierarchy (near/adjacent/xz/y/any)"`

### Task 2: PathBudgets (pure)

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/navigation/PathBudgets.java`
- Modify: `src/main/java/ru/pravets/vasyan/config/VasyanConfig.java` — `[navigation]`: `thinkTimeoutMs=2000 (250..30000)`, `tickTimeoutMs=10 (1..50)`, `searchRadius=64 (16..256)`
- Test: `src/test/java/ru/pravets/vasyan/navigation/PathBudgetsTest.java`

**Interfaces:**
- Produces:
```java
record PathBudgets(long thinkDeadlineNano, long tickDeadlineNano, int searchRadius) {
    static PathBudgets start(long nowNano, long thinkTimeoutMs, long tickTimeoutMs, int searchRadius);
    boolean thinkExpired(long nowNano);
    boolean tickExpired(long nowNano);
    PathBudgets nextTick(long nowNano); // новый tick-deadline, тот же think
}
```

- [ ] **Step 1: failing test**: `start(1000ms budget)` → `thinkExpired(now+1001ms)=true`; `nextTick` обновляет только тик-дедлайн; radius проходит насквозь.
- [ ] **Step 2: RED via CI**, **Step 3: impl**, **Step 4: GREEN**, **Step 5:** `git commit -m "feat(navigation): path budgets (think/tick/radius) + config section"`

### Task 3: PathMonitor — stall/replan/fallback FSM (pure)

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/navigation/PathMonitor.java`
- Test: `src/test/java/ru/pravets/vasyan/navigation/PathMonitorTest.java`

**Interfaces:**
- Consumes: `VasyanGoal` (Task 1).
- Produces:
```java
public final class PathMonitor {
    public enum Decision { CONTINUE, REPLAN, DIG_THROUGH, PLACE_SCAFFOLD, HOP_TELEPORT, GIVE_UP }
    // ticksWithoutProgress до REPLAN, потом лестница fallback'ов
    public PathMonitor(VasyanGoal goal, int stallTicks /*default 40*/, int maxReplans /*default 3*/);
    public Decision onTick(BlockPos botPos, boolean navDone, boolean hasPath,
                           boolean canDig, boolean canPlace);
    public void onProgress();                    // экшен зовёт когда реально сдвинулся/добыл
    public VasyanGoal goal();
    public boolean finished();
}
```

Логика решений: прогресс есть → CONTINUE. Нет прогресса `stallTicks` тиков → REPLAN (пока `replans < maxReplans`). После исчерпания replan'ов → `DIG_THROUGH`, но ТОЛЬКО если glue передал `canDig=true`; иначе → `PLACE_SCAFFOLD` при `canPlace=true`; иначе сразу следующая ступень лестницы. Glue вычисляет флаги ДО вызова onTick: `canDig` = блок по курсу не в blacklist (вода/обсидиан/bedrock), `canPlace` = в инвентаре есть scaffold-блок. Ступень «dig не спас за stallTicks» отслеживает сам монитор по отсутствию прогресса после выдачи DIG_THROUGH.

**Немедленный replan (замечание ревью №4):** `navDone && !goal.hasReached` → REPLAN **без инкремента** счётчика maxReplans (отдельный лимит `navDoneReplans`, default 10) — нестабильная навигация не должна сжечь основные replan'ы до первого настоящего стейла.

- [ ] **Step 1: failing tests** (6 кейсов): прогресс→CONTINUE; stall 40 тиков→REPLAN; replans exhausted→DIG_THROUGH; dig не спас→PLACE_SCAFFOLD; scaffold не спас→HOP_TELEPORT; после teleport-попытки без результата→GIVE_UP; отдельный кейс navDone-вне-цели→немедленный REPLAN.
- [ ] **Step 2: RED via CI**; **Step 3: impl** (FSM на полях-счётчиках, без world); **Step 4: GREEN**; **Step 5:** `git commit -m "feat(navigation): PathMonitor stall/replan/fallback FSM"`

### Task 4: VasyanPathing — server glue

**Files:**
- Create: `src/main/java/ru/pravets/vasyan/navigation/VasyanPathing.java`

**Interfaces:**
- Consumes: PathMonitor, PathBudgets, VasyanGoal.
- Produces:
```java
public final class VasyanPathing {
    /** Начать движение к цели; возвращает монитор для тикания экшеном. */
    public static PathMonitor moveTo(VasyanEntity vasyan, VasyanGoal goal, PathBudgets budgets);
    /** Разовый вызов из action.onTick(): исполняет решение монитора. */
    public static void enforce(VasyanEntity vasyan, PathMonitor monitor);
}
```
`enforce`: REPLAN → `navigation.moveTo(...)` (ground speed 1.0, `setFlying(false)`); DIG_THROUGH → ломает блок непосредственно перед ботом по направлению пути (`level.destroyBlock(pos,false)`), лог + счётчик; PLACE_SCAFFOLD → ставит блок из инвентаря под ноги (если есть); HOP_TELEPORT → одноразовый `teleportTo` (флаг «уже телепортировался» живёт в самом мониторе per-instance: экшен создаёт новый монитор на каждый запуск, лимит естественным образом «1 телепорт на попытку пути»; глобального состояния нет) на ближайшую валидную позицию за препятствием (переиспользует паттерн `VasyanTeleportUtil.findSafePos` — класс в `ru.pravets.vasyan.entity`); GIVE_UP → `navigation.stop()`.

- [ ] **Step 1:** реализация (thin glue; вся логика уже в pure-классах).
- [ ] **Step 2:** `nice ... compileJava` локально (это разрешённая команда) → BUILD SUCCESSFUL.
- [ ] **Step 3:** `git commit -m "feat(navigation): server glue enforcing monitor decisions"`

### Task 5: Перевод PathfindAction на монитор

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/PathfindAction.java`
- Modify: `src/main/java/ru/pravets/vasyan/config/VasyanConfig.java` — `[pathfind] timeoutSeconds` остаётся, но становится бюджетом think
- Test: `src/test/java/ru/pravets/vasyan/action/actions/PathfindActionTest.java` (bootstrap через `McTestBootstrap`, мок Level по образцу `VasyanEntityNbtTest`)

**Interfaces:** PathfindAction теперь: `moveTo(vasyan, GoalNear(pos,2), budgets)` + каждый тик `enforce()`; успех — `goal.hasReached()`, провал — `monitor.finished()==true` ИЛИ think-budget истёк. Удаляются: ручной MAX_TICKS-таймаут и слепой re-moveTo.

- [ ] **Step 1: failing test** (scope: маршрутизация решений монитор→ActionResult, НЕ сам moveTo — VasyanEntity мокается, navigation стаб): mock-navigation `isDone()=true` при далёкой цели → экшен запросил replan (не бесконечный цикл старого кода); GIVE_UP монитора → `ActionResult.failure` с причиной из `describe()`.
- [ ] **Step 2: RED via CI**; **Step 3: переписать экшен**; **Step 4: GREEN + полный прогон в CI (Build)**; **Step 5:** `git commit -m "refactor(pathfind): PathfindAction driven by PathMonitor"`

### Task 6: Вынести stall-копии из GatherResourceAction

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/GatherResourceAction.java`
- Test: существующие тесты gather продолжают зелёные + новые кейсы в `PathMonitorTest` уже покрывают семантику

Что меняется: фаза ROUTING использует `moveTo/enforce` (Task 4); собственные `routeStallCount`/`MINE_STALL_TICKS`/`MAX_ROUTE_STALLS` заменяются мониторными решениями; вода больше не отдельный кейс (amphibious nav + monitor сам разруливает); `unreachableTargets` остаётся (это про видимость целей, не про путь). FELL_* фазы не трогаем в P1.

- [ ] **Step 1:** рефакторинг малыми порциями, каждый запуск — `compileJava` локально.
- [ ] **Step 2:** полный Build в CI ✅ (число тестов >= базовой линии master, регрессий ноль).
- [ ] **Step 3:** `git commit -m "refactor(gather): route phase driven by PathMonitor, drop local stall copies"`

### Task 7: CombatAction/FollowPlayerAction на общий механизм

**Files:**
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/CombatAction.java` — убрать телепорт-костыль (строки ~86–92): вместо него monitor-лестница (teleport теперь легальный последний fallback с логом)
- Modify: `src/main/java/ru/pravets/vasyan/action/actions/FollowPlayerAction.java` — periodic re-moveTo заменить на monitor REPLAN

- [ ] **Step 1:** правки + локальный compileJava.
- [ ] **Step 2:** Build CI ✅.
- [ ] **Step 3:** `git commit -m "refactor(combat,follow): unified PathMonitor handling"`

### Task 8: Behavior-тесты (RCON сценарии)

**Files:**
- Modify: `scripts/behavior/behavior_test.py` — добавить сценарии:
  1. `test_river_crossing`: бот за рекой шириной 4 (water channel в суперплоском мире), команда «иди к <координаты за рекой>», assert: лог «Reached target position» за N секунд, бот НЕ телепортировался (позиция непрерывность по логам).
  2. `test_adjacent_stand`: блок обсидиана, «подойди к блоку», assert: манхэттен-дистанция до блока == 1 (встал сбоку, не сверху).
  3. `test_wall_dig_through`: стена 2×2 из земли, цель за ней, assert: дошёл, в логе был DIG_THROUGH.

- [ ] **Step 1:** сценарии по образцу существующего `test_chunk_persists_after_restart` (persistent-buffer RCON-клиент; для повторных спавнов брать последний матч: `re.findall(...)[:-1]`).
- [ ] **Step 2:** `gh workflow run behavior-tests --ref <branch>` ✅.
- [ ] **Step 3:** `git commit -m "test(behavior): river crossing, adjacent stand, wall dig-through scenarios"`

### Task 9: Финализация — PR

- [ ] **Step 1:** push ветки `feat/phase06-p1-pathfinding-hygiene` (от свежего master!), оба CI зелёные.
- [ ] **Step 2:** PR в master, описание: что/зачем/скриншоты логов сценариев, `Closes #35` (P2/P3 останутся открытыми вопросами — переоткрыть #35 как P2/P3-tracking или завести отдельный issue).
- [ ] **Step 3:** CodeRabbit review → фикс замечаний → мердж по команде юзера.

## Risks / Open Questions

1. **Amphibious navigation vs GoalAdjacent**: водная цель может стоять «вплотную» иначе (плавание). Принято: adjacency считается в 3D-манхэттене, плавающие позиции валидны.
2. **DIG_THROUGH в P1** — минимальный: ломаем только блок прямо по курсу, без выбора лучшего инструмента (это P2). Риск испортить чужие постройки — mitigated: только если путь заблокирован И replan исчерпан И блок не из blacklist (вода/обсидиан/bedrock).
3. **HOP_TELEPORT частота**: чтобы бот не стал «телепортером» — жёсткий лимит: не чаще 1 раза на экшен + всегда WARN в лог.
4. **Behavior-тесты flaky** (реальный мир, тайминги): таймауты щедрые (60с), ассерты на «дошёл/не дошёл», не на точный путь.
