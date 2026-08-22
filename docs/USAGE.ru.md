# Руководство по использованию Vasyan

Vasyan — мод для Minecraft 1.20.1 (Forge), добавляющий в Minecraft автономных ИИ-агентов.

## Установка

1. Скачайте `vasyan-ai-mod-<version>-all.jar` из GitHub Releases.
2. Поместите JAR в папку `mods` вашего экземпляра Minecraft (должен быть установлен Minecraft 1.20.1 с Forge).
3. Запустите Minecraft.
4. Скопируйте `config/vasyan-common.toml.example` в `config/vasyan-common.toml`.
5. Добавьте ключ API и выберите провайдера.

## Спавн бота

Откройте чат и выполните:

```
/vasyan spawn Bob
```

Имена могут содержать буквы (любой скрипт), цифры и `_ - . +`. Поддерживаются кириллические имена.

## Основные команды

- `/vasyan list` — показать активных ботов.
- `/vasyan stop <имя>` — остановить все задачи бота.
- `/vasyan remove <имя>` — удалить бота из мира.
- `/vasyan tp <имя>` — телепортировать бота к вам.
- `/vasyan tell <имя> <задача>` — дать задачу на естественном языке.
- `/vasyan inv <имя>` — открыть инвентарь бота.
- `/vasyan dump <имя> [with-prompt]` — сохранить полное состояние бота в `logs/vasyan-dumps/<bot>-<timestamp>.json`.
- `/vasyan look <имя>` — краткое детерминированное описание окружения бота.

## Задачи на естественном языке

Нажмите **K**, чтобы открыть панель Vasyan, или используйте `/vasyan tell`:

- "mine 20 iron ore"
- "build a small house here"
- "follow me"
- "gather wood from that forest"
- "что ты видишь?" — получить краткое детерминированное описание окружения выбранного бота.

## Конфигурация

См. `config/vasyan-common.toml`:

```toml
[llm]
provider = "opencode-go"
baseUrl = "https://opencode.ai/zen/go/v1"
apiKey = "your-key"
model = "deepseek-v4-flash"
```

## Ссылки

- Репозиторий: https://github.com/pravets/Vasyan
- Апстрим: https://github.com/YuvDwi/Steve (MIT)

## Голосовые команды

Зажмите **V** и говорите команду. Аудио транскрибирует STT-провайдер на стороне сервера, а текст выполняется как обычная команда.

Включите в `config/vasyan-common.toml`:

```toml
[voice]
enabled = true
sttBaseUrl = "https://routerai.ru/api/v1"
sttApiKey = "your-stt-key"
sttModel = "openai/whisper-large-v3-turbo"
sttLanguage = "ru"
```

Подойдёт любой OpenAI-совместимый эндпоинт `/audio/transcriptions`.
