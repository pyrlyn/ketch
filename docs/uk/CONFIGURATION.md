---
lang: uk
---

# Конфігурація

`~/.ketch/config.toml`; змінні середовища мають пріоритет:

| Ключ | Змінна середовища | За замовчуванням |
| --- | --- | --- |
| `apps_dir` | `KETCH_APPS_DIR` | `/Applications` |
| `github_token` | `KETCH_GITHUB_TOKEN`, `GITHUB_TOKEN`, `GH_TOKEN` | немає |
| `prerelease` | `KETCH_PRERELEASE` | `false` |
| `allow_emulation` | `KETCH_ALLOW_EMULATION` | `true` |
| `link_apps` | `KETCH_LINK_APPS` | `false` |
| `require_checksums` | `KETCH_REQUIRE_CHECKSUMS` | `false` |
| `strip_quarantine` | `KETCH_STRIP_QUARANTINE` | `true` |
| `auto_update` | `KETCH_AUTO_UPDATE` | `true` |
| `emoji` | `KETCH_EMOJI` | `true` |
| `registry` | `KETCH_REGISTRY` | `pyrlyn/ketch-registry` |
| `self_repo` | `KETCH_SELF_REPO` | `pyrlyn/ketch` |
| `jobs` | `KETCH_JOBS` | `4` (не більше `16`) |
| `log_level` | `KETCH_LOG_LEVEL` | `info` |
| `log_format` | `KETCH_LOG_FORMAT` | `text` |
| `max_download_mib` | `KETCH_MAX_DOWNLOAD_MIB` | `4096` |

`self_repo` і `registry`, що вказують на колишні імена репозиторіїв до переїзду,
`listepo/ketch` і `listepo/ketch-registry`, читаються як імена в `pyrlyn`; те саме
стосується старих імен у файлі стану та в lock-файлах.

[docs/config.schema.json](https://github.com/pyrlyn/ketch/blob/main/docs/config.schema.json) — JSON Schema цього файлу,
згенерована з типів, у які ketch його читає; вона потрібна редакторам і лінтерам.

Щоб почати заново, `ketch config reset` записує `config.toml` з цими значеннями за
замовчуванням (після запитання, якщо не вказано `--yes`). Наявний файл зберігається поруч
як `config.toml.bak-<unix-seconds>`, якщо він є і ще не збігається з сусідньою резервною
копією:

```bash
ketch config reset          # запитати, зберегти копію, записати значення за замовчуванням
ketch config reset --yes    # для скриптів і CI
```

`auto_update` (за замовчуванням `true`) запускає `ketch update` на початку `install` і
`upgrade`. Встановіть `false` або `KETCH_AUTO_UPDATE=false`, щоб не оновлювати реєстр.

`emoji` (за замовчуванням `true`) ставить значок перед кожним рядком статусу в терміналі:
📦 встановлення, ⏫ оновлення, 🧹 видалення, ⏬ завантаження, 🔗 посилання, ⏪ відкат,
✅ успіх, ❗ попередження, ❌ помилка, 💡 примітка. Встановіть `false`, `KETCH_EMOJI=0` або
передайте `--no-emoji`, щоб обійтися без них. Значки ніколи не потрапляють у пайп,
`TERM=dumb`, вивід `--json` чи журнал.

Сам корінь задається `KETCH_ROOT` або `--root`; у файлі конфігурації його задати не можна,
бо файл лежить усередині кореня. `KETCH_GITHUB_API` перевизначає базовий URL API GitHub
(для Enterprise); його теж не можна задати у файлі конфігурації. `jobs` і `--jobs`
більше `16` обмежуються значенням `16`.

`max_download_mib` — найбільше одиночне завантаження, яке записує ketch, у МіБ.
Завантаження, що оголошує більший розмір або мовчки його перевищує, зупиняється і не
залишає файлу; збільште значення для пакета, який справді більший.

Токен не обов'язковий, але помітно підвищує ліміт запитів GitHub.
