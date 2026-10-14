---
lang: ru
---

# Конфигурация

`~/.ketch/config.toml`; переменные окружения имеют приоритет:

| Ключ | Переменная окружения | По умолчанию |
| --- | --- | --- |
| `apps_dir` | `KETCH_APPS_DIR` | `/Applications` |
| `github_token` | `KETCH_GITHUB_TOKEN`, `GITHUB_TOKEN`, `GH_TOKEN` | нет |
| `prerelease` | `KETCH_PRERELEASE` | `false` |
| `allow_emulation` | `KETCH_ALLOW_EMULATION` | `true` |
| `link_apps` | `KETCH_LINK_APPS` | `false` |
| `require_checksums` | `KETCH_REQUIRE_CHECKSUMS` | `false` |
| `strip_quarantine` | `KETCH_STRIP_QUARANTINE` | `true` |
| `auto_update` | `KETCH_AUTO_UPDATE` | `true` |
| `emoji` | `KETCH_EMOJI` | `true` |
| `registry` | `KETCH_REGISTRY` | `pyrlyn/ketch-registry` |
| `self_repo` | `KETCH_SELF_REPO` | `pyrlyn/ketch` |
| `jobs` | `KETCH_JOBS` | `4` (не больше `16`) |
| `log_level` | `KETCH_LOG_LEVEL` | `info` |
| `log_format` | `KETCH_LOG_FORMAT` | `text` |
| `max_download_mib` | `KETCH_MAX_DOWNLOAD_MIB` | `4096` |

`self_repo` и `registry`, указывающие на прежние имена репозиториев до переезда,
`listepo/ketch` и `listepo/ketch-registry`, читаются как имена в `pyrlyn`; то же
относится к старым именам в файле состояния и в lock-файлах.

[docs/config.schema.json](https://github.com/pyrlyn/ketch/blob/main/docs/config.schema.json) — JSON Schema этого файла,
сгенерированная из типов, в которые ketch его читает; она нужна редакторам и линтерам.

Чтобы начать заново, `ketch config reset` записывает `config.toml` с этими значениями
по умолчанию (после вопроса, если не указан `--yes`). Существующий файл сохраняется
рядом как `config.toml.bak-<unix-seconds>`, если он есть и ещё не совпадает с соседней
резервной копией:

```bash
ketch config reset          # спросить, сохранить копию, записать значения по умолчанию
ketch config reset --yes    # для скриптов и CI
```

`auto_update` (по умолчанию `true`) запускает `ketch update` в начале `install` и
`upgrade`. Установите `false` или `KETCH_AUTO_UPDATE=false`, чтобы не обновлять реестр.

`emoji` (по умолчанию `true`) ставит значок перед каждой строкой статуса в терминале:
📦 установка, ⏫ обновление, 🧹 удаление, ⏬ загрузка, 🔗 ссылка, ⏪ откат, ✅ успех,
❗ предупреждение, ❌ ошибка, 💡 примечание. Установите `false`, `KETCH_EMOJI=0` или
передайте `--no-emoji`, чтобы обойтись без них. Значки никогда не попадают в пайп,
`TERM=dumb`, вывод `--json` или журнал.

Сам корень задаётся `KETCH_ROOT` или `--root`; в файле конфигурации его задать нельзя,
потому что файл лежит внутри корня. `KETCH_GITHUB_API` переопределяет базовый URL API
GitHub (для Enterprise); его тоже нельзя задать в файле конфигурации. `jobs` и `--jobs`
больше `16` ограничиваются значением `16`.

`max_download_mib` — самая большая одиночная загрузка, которую записывает ketch, в МиБ.
Загрузка, которая объявляет больший размер или молча его превышает, останавливается и не
оставляет файла; увеличьте значение для пакета, который действительно больше.

Токен не обязателен, но заметно повышает лимит запросов GitHub.
