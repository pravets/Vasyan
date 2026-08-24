# Vasyan Roadmap

Approved 2026-08-21 with Иосиф Правец.

## Identity

- **Name:** Vasyan
- **GitHub repo:** `pravets/Vasyan`
- **Maven group:** `ru.pravets.vasyan`
- **Mod ID:** `vasyan`
- **Class prefix:** `Vasyan*`
- **Package:** `ru.pravets.vasyan`
- **Artifact:** `vasyan-ai-mod`
- **Display name:** Vasyan AI mod

## Constraints

- **Version:** 1.20.1; GTNH-port отложен до стабилизации текущей версии.
- **Definition of Done:** зелёный CI + unit/behavior тесты + live Minecraft test.
- **One PR = one task.**
- **Branch from `master` only.**
- **Local VPS build:** `nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1`.
- **Full build + behavior tests:** GitHub CI.
- **Upstream MIT attribution to `YuvDwi/Steve` is preserved.**

## Phases

### Phase 0 — Rebrand ✅

1. Branch `feat/rebrand-steve-to-vasyan` from `master`.
2. Rename Java package `com.steve.ai` → `ru.pravets.vasyan`.
3. Rename `Steve*` classes → `Vasyan*` (`VasyanMod`, `VasyanEntity`, `VasyanManager`, etc.).
4. Update mod ID, entity/item/block IDs, network channel, lang keys, `mods.toml`.
5. Update `build.gradle` group/archivesBaseName and `settings.gradle` rootProject name.
6. Rename asset folder, example config file, helper scripts.
7. Update CI workflows and behavior-test scripts.
8. Add bilingual docs (`docs/USAGE.ru.md`, `docs/USAGE.en.md`), `AGENTS.md`, GitHub PR/issue templates.
9. Preserve MIT attribution to `YuvDwi/Steve`.
10. Verify local `compileJava compileTestJava`, push PR, wait for green CI, merge, rename GitHub repo to `pravets/Vasyan`.

### Phase 0.5 — Respawn bugfix ✅ (PR #25)

- Боты сейчас респавнятся рядом с игроком после релога вместо восстановления сохранённой позиции.
- Сохранять позицию/инвентарь/память в NBT и `adopt` из мира при логине.
- Результат: после рестарта бот остаётся там, где был.
- Фактический корень бага: `VasyanInventory` сохранял предметы компактным списком без индексов слотов — после загрузки инвентарь «съезжал». Исправлено: слоты сохраняются с индексами + legacy-fallback.

### Phase 0.6 — Pathfinding overhaul

**Проблема.** Навигация — слабейшее место мода на фоне mindcraft (mineflayer-pathfinder) и Baritone. Сейчас:

- `PathfindAction` — наивный `moveTo(x,y,z)` + таймаут 600 тиков; застревание не диагностируется.
- Обходы размазаны по экшенам: `GatherResourceAction` держит собственные stall-детекции (вода/деревья/шахта), `CombatAction` телепортируется при застревании. Пять копий одной логики в разных местах.
- Ванильный `GroundPathNavigation`/`WalkNodeEvaluator` не умеет ломать/ставить блоки как часть пути.
- Долгие маршруты (>600 тиков) невозможны.

**Вдохновение:** goals-as-conditions, Movements-цены, think/tick-бюджеты и авто-replan из [mineflayer-pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder); сегментированный путьинг, кэш известного мира и пресеты риск/скорость из [Baritone](https://github.com/cabaletta/baritone). Реализация — серверно, через кастомный `PathNavigation`+`NodeEvaluator` (прецедент: MineColonies), без внешних движков.

#### P1 — Гигиена навигации (маленький дифф, большой эффект)

1. Иерархия целей `VasyanGoal`: `GoalNear(pos, range)`, `GoalAdjacent(block)` (встать у любой из 6 сторон — для копки/строительства), `GoalXZ`, `GoalY`, `GoalCompositeAny(goals...)`.
2. Единый `PathMonitor`: stall-детекция (нет прогресса N тиков) + fallback-лестница replan → dig/place → hop-teleport. Вынести сюда логику воды/застревания из `GatherResourceAction`/`CombatAction`.
3. Бюджеты: `thinkTimeout` (общий мс), `tickTimeout` (мс на тик), `searchRadius` — планирование пути не фризит сервер.
4. Юнит-тесты на генерацию ходов (`AbstractMinecraftTest`) + RCON-сценарии в behavior_test.py («пройти реку», «встать adjacent к блоку»).

#### P2 — Движок с копанием/строительством

1. Кастомный `NodeEvaluator` (расширение `WalkNodeEvaluator`) с ходами DIG / PLACE / PILLAR-UP как рёбрами графа.
2. Цены ходов в `VasyanConfig`: `digCost`, `placeCost`, `liquidCost`, `entityCost`, `maxDropDown`; scaffold-whitelist блоков.
3. Выбор лучшего инструмента из `VasyanInventory` перед DIG-ходом (аналог `bestHarvestTool`).
4. Правила безопасности: `dontCreateFlow` (не ломать рядом с жидкостью), не копать под падающими блоками.
5. Авто-replan при изменении мира по пути.

#### P3 — Дальние маршруты

1. Сегментированный A*: маршрут режется по бюджету узлов, сегменты сшиваются — снимает лимит 600 тиков.
2. Планирование в неисследованные чанки по кэшу `WorldKnowledge`.
3. Пресеты движения safe/bold (bold: спринт, паркур, прыжки в воду) — конфиг + выбор через команду/LLM-планировщик.

**DoD:** зелёный CI + behavior-тесты «бот доходит туда, куда раньше не доходил» (река, стена, вертикальный обрыв, дальний маршрут >1000 блоков).

### Phase 1 — Diagnostics & visibility

- Команда `/vasyan dump <name>` — сохраняет полное состояние бота в `logs/vasyan-dumps/<bot>-<timestamp>.json`.
  - По умолчанию включает ответ LLM.
  - Промпт — только по флагу `with-prompt`.
- Команда «что ты видишь?» — запросить краткое описание окружения от бота.

### Phase 2 — Waypoints, return, inventory, XP

- **Waypoints:** именованные NBT-персистентные точки + авто-`worksite`; формат с заделом на будущий экспорт в GTNH teleport points.
- **Return:** бот возвращается к `worksite` по команде.
- **Unload MVP:** бот подлетает к игроку, чтобы игрок забрал ресурсы вручную. Сундуки/ME — позже.
- **Bidirectional inventory:** бот может получать инструменты/материалы от игрока (read-only отменяется).
- **XP:** бот накапливает XP и по команде спавнит Experience Orb рядом с игроком.

### Phase 3 — Survival crafting

- 2×2 и 3×3 крафт.
- Поиск рецепта по названию/ингредиентам.
- Авто-крафт инструмента при поломке и по команде.
- Начать с инструментов и сундуков.

### Phase 4 — Mining

- Поверхностная добыча руды в горах.
- Спуск 2×2 винтовой лестницей.
- Branch mining с конфигурируемым шагом/высотой.
- Whitelist/blacklist руд (конфиг + команда).

### Phase 5 — Survival building

- Строительство из блоков в инвентаре бота.
- Без полёта/телепорта — ходьба, прыжки, размещение блоков.
- Разбор площадки перед постройкой.
- Повторное использование `StructureGenerators` (дома, башни, замки).

### Phase 6 — Advanced scouting

- Память ресурсов на уровне чанков.
- Нумерованные resource points.
- Аккуратный vein mining.

### Phase 7 — Publication

- Резервирование slug `vasyan` на Modrinth и CurseForge.
- Release workflow в GitHub Actions.
- Публикация релиза.

## Multi-agent

- Дальний бэклог, не в текущих фазах.

## Open questions / resolved

- **Full rebrand scope:** mod ID, Maven group `ru.pravets.vasyan`, Java packages, entity ID, lang keys, network channel, config sections, GitHub repo rename — да.
- **Dump location:** `logs/vasyan-dumps/<bot>-<timestamp>.json`.
- **Dump content:** LLM response по умолчанию, prompt только по флагу `with-prompt`.
- **Mining mode:** branch mining + поверхность + 2×2 spiral down.
- **Unload MVP:** подлёт к игроку для ручного забора.
- **XP transfer:** Experience Orb рядом с игроком.
