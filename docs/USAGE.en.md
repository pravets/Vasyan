# Vasyan Usage Guide

Vasyan is a Minecraft 1.20.1 mod (Forge) that adds autonomous AI agents to Minecraft.

## Installation

1. Download `vasyan-ai-mod-<version>-all.jar` from the GitHub releases.
2. Put the JAR into your Minecraft instance's `mods` folder (Minecraft 1.20.1 with Forge must already be installed).
3. Start Minecraft.
4. Copy `config/vasyan-common.toml.example` to `config/vasyan-common.toml`.
5. Add your API key and choose a provider.

## Spawning a bot

Open chat and run:

```
/vasyan spawn Bob
```

Names may contain letters (any script), digits and `_ - . +`. Cyrillic names are supported.

## Basic commands

- `/vasyan list` — show active bots.
- `/vasyan stop <name>` — stop all tasks for a bot.
- `/vasyan remove <name>` — remove a bot from the world.
- `/vasyan tp <name>` — teleport a bot to a safe spot near you.
- `/vasyan tell <name> <task>` — give a natural-language task.
- `/vasyan inv <name>` — open the bot's inventory.
- `/vasyan dump <name> [with-prompt]` — save full bot state to `logs/vasyan-dumps/<bot>-<timestamp>.json`.
- `/vasyan look <name>` — brief deterministic description of the bot's surroundings.

## Natural-language tasks

Press **K** to open the Vasyan panel, or use `/vasyan tell`:

- "mine 20 iron ore"
- "build a small house here"
- "follow me"
- "gather wood from that forest"
- "what do you see?" — get a brief deterministic description of the selected bot's surroundings.

## Configuration

See `config/vasyan-common.toml`:

```toml
[llm]
provider = "opencode-go"
baseUrl = "https://opencode.ai/zen/go/v1"
apiKey = "your-key"
model = "deepseek-v4-flash"
```

## Links

- Repository: https://github.com/pravets/Vasyan
- Upstream: https://github.com/YuvDwi/Steve (MIT)

## Voice commands

Hold **V** to record a voice command. The audio is transcribed by the server-side STT provider and executed as text.

Enable in `config/vasyan-common.toml`:

```toml
[voice]
enabled = true
sttBaseUrl = "https://routerai.ru/api/v1"
sttApiKey = "your-stt-key"
sttModel = "openai/whisper-large-v3-turbo"
sttLanguage = "ru"
```

Any OpenAI-compatible `/audio/transcriptions` endpoint works.
