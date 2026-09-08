# Phase 0.6 P2 — Движок путей с копанием/строительством

**Дата:** 2026-08-31  
**Автор:** Iosif Pravets <i@pravets.ru>  
**Статус:** согласован, готов к планированию  
**База:** `feat/phase06-p2-dig-place-pathfinding`  
**Связи:** Closes #49, продолжает P1 (PR #39 / issue #35).

## Контекст

P1 ввёл единый `PathMonitor`, `VasyanGoal`-иерархию, бюджеты и recovery-лестницу:
`replan → vertical → digThrough → placeScaffold → hopTeleport`.  
Копание и постановка блоков в P1 — **аварийное восстановление** поверх ванильного
`AmphibiousPathNavigation`, который не умеет планировать маршрут со сломанным/
поставленным блоком.

Цель P2: сделать DIG / PLACE / PILLAR-UP **рёбрами графа пути**, чтобы бот
*сразу* строил маршрут через стену/ров/обрыв, а recovery-лестница срабатывала
только как страховка.

## Скоуп P2

1. **Кастомный `NodeEvaluator`** (`VasyanNodeEvaluator extends WalkNodeEvaluator`)
   с рёбрами DIG / PLACE / PILLAR-UP.
2. **Кастомный `PathFinder`** (`VasyanPathFinder extends PathFinder`) под
   evaluator + бюджеты P1.
3. **Кастомная навигация** (`VasyanPathNavigation extends AmphibiousPathNavigation`)
   для исполнения DIG/PLACE-узлов пути.
4. **Цены ходов в `VasyanConfig`**: `digCost`, `placeCost`, `liquidCost`,
   `entityCost`, `maxDropDown`, `digHardnessFactor`, `scaffoldWhitelist`.
5. **Правила безопасности**: `dontCreateFlow`, защита от падающих блоков,
   reuse ограничений P1 (руды и unbreakable не ломаем).
6. **Авто-replan**: проверка ближайших узлов пути на соответствие миру.
7. **Тесты**: unit JUnit (цены + безопасность), McTestBootstrap (генерация
   рёбер), behavior RCON (новый сценарий K).
8. **Документация**: обновить `docs/CAPABILITIES.md` и `ROADMAP.md` после мерджа.

## Вне скоупа (P3)

- Сегментированный A* и маршруты >600 тиков.
- Планирование в неисследованные чанки по `WorldKnowledge`.
- Пресеты движения safe/bold.
- Реальные инструменты из инвентаря (survival-crafting phase).

## Архитектура

### Компоненты

```text
VasyanEntity.createNavigation()
        │
        ▼
VasyanPathNavigation  (extends AmphibiousPathNavigation)
        │
        ├── createPathFinder() ──► VasyanPathFinder
        │                                │
        │                                ▼
        │                       VasyanNodeEvaluator
        │                       (DIG / PLACE / PILLAR-UP рёбра)
        │
        └── followPath() ──► исполняет DIG/PLACE/PILLAR-узлы
                            (пауза-мутация)

VasyanPathing          (stateless glue из P1)
PathMonitor            (recovery-лестница, остаётся страховкой)
VerticalTraversalPlanner (vertical recovery из P1)
```

### NodeEvaluator

`VasyanNodeEvaluator` расширяет `WalkNodeEvaluator`. В переопределённом
`getNeighbors()` сначала добавляет ванильные ходы; затем, если ванильный ход
невозможен:

- **DIG**: впереди breakable блок, который разрешено ломать (не руда, не bedrock,
  не obsidian) → соседняя позиция за препятствием с ценой
  `base + digCost × hardnessFactor(block)`.
- **PLACE**: впереди обрыв/яма глубже `maxDropDown`, и в инвентаре есть
  scaffold-блок (см. `ScaffoldBlocks`) → узел-мост с ценой `base + placeCost`.
- **PILLAR-UP**: цель на 2+ блока выше, нет лестницы, есть scaffold-блок →
  ребро «поставить под себя + подняться», цена `placeCost + walkCost`.

Тип каждого узла (MoveType) хранится внутри инстанса evaluator и доступен
навигации через `getMoveType(node)`.

### PathNavigation

`VasyanPathNavigation`:

- Подменяет `createNavigation` у `VasyanEntity` для всех маршрутов.
- `createPathFinder()` возвращает `VasyanPathFinder` с нашим evaluator и
  бюджетами `NAV_THINK_TIMEOUT_MS`, `NAV_TICK_TIMEOUT_MS`, `NAV_SEARCH_RADIUS`.
- `followPath()` проверяет тип текущего/следующего узла:
  - **DIG**: остановить движение, swing, `destroyBlock(pos, true)`
    (дропы вакууму, как P1), затем продолжить путь.
  - **PLACE**: взять лучший scaffold-стек, `setBlockAndUpdate`, shrink, продолжить.
  - **PILLAR-UP**: поставить блок под ноги, выполнить прыжок/подъём, продолжить.
  - **WALK**: стандартное поведение `AmphibiousPathNavigation`.

Одна мутация — один тик. Пока выполняется DIG/PLACE, `MoveControl` стоит.

### Цены и конфиг

Новые ключи в секции `[navigation]`:

| Ключ | Тип | Дефолт | Назначение |
|---|---|---|---|
| `navDigCost` | int | 4 | Базовая цена DIG-ребра. |
| `navPlaceCost` | int | 4 | Базовая цена PLACE/PILLAR-ребра. |
| `navLiquidCost` | int | 20 | Доп. цена/штраф при риске создать поток. |
| `navEntityCost` | int | 8 | Доп. цена прохождения рядом с мобами. |
| `navMaxDropDown` | int | 3 | Макс. высота спуска без PLACE-ребра. |
| `navDigHardnessFactor` | double | 1.0 | Множитель hardness блока к цене DIG. |
| `navScaffoldWhitelist` | List<String> | `["dirt", "cobblestone"]` | Доп. whitelist блоков для scaffold (помимо существующего скоринга). |
| `navReplanCheckIntervalTicks` | int | 10 | Период проверки ближайших узлов. |

Инструменты виртуальные: hardness блока влияет на цену DIG-ребра, но
необходимость подходящего инструмента не проверяется.

### Правила безопасности

- **dontCreateFlow**: DIG запрещено/дорого, если соседняя ячейка содержит
  источник или поток жидкости. Лава всегда запрещает DIG (reuse
  `isUnsafeLiquid`).
- **Падающие блоки**: DIG запрещен в ячейке, над которой в столбце выше есть
  sand / gravel / anvil.
- **Ограничения P1**: NEVER_BREAK (руды) и UNBREAKABLE (bedrock/obsidian и др.)
  остаются; DIG-ребро не генерируется для них.

### Авто-replan

В `VasyanPathNavigation.tick()` каждые `navReplanCheckIntervalTicks` тиков
проверяются ближайшие ~5 узлов пути:

- walk-узел стал непроходимым (solid блок в ногах/голове) → `recomputePath()`;
- dig-узел — блок уже сломан (нормально, ждём исполнения);
- place-узел — ячейка заполнена чужим блоком → `recomputePath()`.

### Связь с P1

- `PathMonitor` и recovery-лестница **сохраняются**.
- DIG_THROUGH / PLACE_SCAFFOLD recovery теперь срабатывают редко —
  только если planner не предусмотрел препятствие или мир изменился резко.
- Pit-escape, `VerticalTraversalPlanner` и `VasyanPathing` остаются.
- Поведения `GatherResourceAction` (whole-tree felling, вакуум дропов) не
  меняются.

## Файлы

### Новые

- `src/main/java/ru/pravets/vasyan/navigation/VasyanNodeEvaluator.java`
- `src/main/java/ru/pravets/vasyan/navigation/VasyanPathFinder.java`
- `src/main/java/ru/pravets/vasyan/navigation/VasyanPathNavigation.java`
- `src/main/java/ru/pravets/vasyan/navigation/MoveType.java`
- `src/main/java/ru/pravets/vasyan/navigation/DigPlaceCosts.java`
- `src/main/java/ru/pravets/vasyan/navigation/DigRules.java`
- `src/main/java/ru/pravets/vasyan/navigation/ScaffoldBlocks.java`

### Изменённые

- `src/main/java/ru/pravets/vasyan/entity/VasyanEntity.java` — `createNavigation()`
- `src/main/java/ru/pravets/vasyan/config/VasyanConfig.java` — новые ключи
- `src/main/java/ru/pravets/vasyan/navigation/VasyanPathing.java` — вынести
  `isBreakable`, UNBREAKABLE/NEVER_BREAK, scaffold-скоринг в `DigRules`/`ScaffoldBlocks`
- `scripts/behavior/behavior_test.py` — сценарий K
- `docs/CAPABILITIES.md` — описание новых возможностей
- `ROADMAP.md` — отметить P2 выполненным

## DoD / Тесты

1. **Unit (plain JUnit)**:
   - `DigPlaceCostsTest` — цены DIG/PLACE/PILLAR.
   - `DigRulesTest` — dontCreateFlow, падающие блоки, ore/bedrock запреты.
2. **Unit (McTestBootstrap)**:
   - `VasyanNodeEvaluatorTest` — генерация DIG-ребра перед стеной.
   - `VasyanPathNavigationTest` — PLACE-ребро через яму, PILLAR-UP на обрыв.
3. **Behavior (RCON)**:
   - Сценарий K: маршрут «стена → ров → обрыв» проходится без срабатывания
     recovery-лестницы (лог не содержит `DIG_THROUGH`/`PLACE_SCAFFOLD` после
     первоначального планирования).
4. **Регрессия**:
   - Сценарии A–J из P1 продолжают проходить.
   - `./gradlew compileJava compileTestJava` зелёный.
   - GitHub CI build + behavior-tests зелёные.
   - Живая проверка в Minecraft.

## Подходы (обсуждённые и выбранные)

| Вопрос | Выбор | Почему |
|---|---|---|
| Модель инструментов | Виртуальные (hardness влияет на цену) | Реальные инструменты — отдельная фаза survival crafting; минимизируем скоуп P2. |
| Исполнение DIG/PLACE | Пауза-мутация в `followPath()` | Честно, просто отлаживать, не нужен второй executor. |
| PILLAR-UP | В графе пути | Issue #49 явно требует; vertical recovery P1 остаётся страховкой. |
| Внедрение | Сразу для всех маршрутов | Единый код-путь; recovery-лестница прикрывает регрессии. |
| Авто-replan | Проверка ближайших узлов | Дёшево и достаточно; глобальный BlockEvent-слушатель дороже. |

## Следующий шаг

После утверждения спеки — `writing-plans` skill для детального плана
реализации.
