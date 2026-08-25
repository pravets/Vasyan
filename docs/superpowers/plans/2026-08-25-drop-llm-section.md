# Plan v3 (final): один файл — vasyan-common.toml

## Решение

`vasyan-llm-members.toml` НЕ вводим (LlmMembersFile удаляем).
Все настройки членов цепочки — в `vasyan-common.toml` под `[llm.members.<preset-id>]`.
14 пресетных id объявлены в схеме статически → Forge их не стирает.
Произвольные имена не поддерживаются; свой эндпоинт = `[llm.members.custom]`.

## Резолв per-member (два уровня)

| Поле | [llm.members.<id>] | Пресет |
|---|---|---|
| baseUrl | override | preset.baseUrl |
| apiKey | override | нет |
| model | override | preset.defaultModel |

Общих llm.provider/baseUrl/apiKey/model/jsonMode больше нет.

## Что удаляем из [llm]

provider, baseUrl, apiKey, model, jsonMode. Остаются: providerChain,
failoverRetrySeconds, maxTokens, temperature, timeoutSeconds, planningTimeoutSeconds.

## Файлы

1. **VasyanConfig**
   - Удалить: AI_PROVIDER, LLM_BASE_URL, LLM_API_KEY, LLM_MODEL, LLM_JSON_MODE
   - MEMBER_* остаются как есть
   - Миграция: если старые поля непусты при загрузке → WARN «перенесите в [llm.members.<id>]»
2. **TaskPlanner**
   - buildProviderChain: резолв только section→preset; удалить fileSettings,
     shared-fallbacks, primaryResilient/headWithNoOverrides ветку
   - Удалить memberSection()? НЕТ — оставить, это маппинг id→MEMBER_*
   - Упростить конструктор: head строится как остальные
3. **VasyanCommands**: providers/debug — resolved per-member без упоминаний
   общих полей; убрать firstNonBlank-обёртки над resolveBaseUrl где можно
4. **LlmMembersFile.java** — УДАЛИТЬ (+ вызов reload() в VasyanMod)
5. **config/vasyan-llm-members.toml.example** — УДАЛИТЬ
6. **config/vasyan-common.toml.example** — переписать: chain + members-секции с примерами
7. **Тесты**: grep AI_PROVIDER/LLM_MODEL/LLM_API_KEY/LlmMembersFile → обновить
8. **AGENTS.md**: блок LLM notes переписать

## DoD

- test зелёный
- Чистый конфиг: chain=["tokenra"] + [llm.members.tokenra] apiKey/model → бот отвечает
- chain=["opencode-go","ollama"] без members-секций → работают на пресетах
- /vasyan providers показывает resolved url/model/key per-member
- Кастомный эндпоинт: [llm.members.custom] baseUrl + "custom" в chain
