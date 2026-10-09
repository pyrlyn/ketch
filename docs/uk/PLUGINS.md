---
lang: uk
---

# Плагіни джерел

GitHub вбудований. Усе інше — GitLab, Gitea, внутрішній сервер артефактів —
можна додати плагіном, без перекомпіляції ketch і без нового релізу ketch.

Плагін — це виконуваний файл з назвою `ketch-source-<scheme>`. ketch запускає його з
підкомандою й читає один JSON-документ з його stdout. Це весь
контракт, тому плагін можна написати будь-якою мовою.

Закомічений [`ketch-source-example`](../../examples/ketch-source-example) — це
той самий скрипт, що нижче, з правами на виконання, щоб його можна було одразу скопіювати в
`~/.ketch/plugins` і спробувати через `ketch plugin list`.

## Виявлення

ketch шукає спершу в `~/.ketch/plugins`, потім в усіх каталогах із `PATH`.
Перемагає перший знайдений `ketch-source-<name>`, тому копія в каталозі плагінів
перекриває копію з `PATH`. `ketch plugin list` показує знайдене, а `ketch
plugin dir` друкує каталог.

Кожен виявлений плагін запитують про його можливості під час кожної команди ketch, тому
`capabilities` має працювати швидко й не звертатися до мережі.

## Підкоманди

```text
capabilities              -> {"protocol":1,"scheme":"gitlab","download":false,"search":true}
describe <id>             -> a SourceInfo object, or null
releases <id> [--limit N] [--prerelease]
                          -> [ Release, ... ]
search <query> --limit N  -> [ SourceInfo, ... ]
download <url> <dest>     -> only when capabilities.download is true
```

`KETCH_PROTOCOL_VERSION` задається в оточенні під час кожного виклику.

Завершіться з кодом `0`, вивівши JSON-документ у stdout. Усе інше вважається помилкою, і
все, що плагін написав у stderr, показується користувачеві.

### `capabilities`

```json
{ "protocol": 1, "scheme": "gitlab", "download": false, "search": true }
```

| Поле | Обов'язкове | Значення |
| --- | --- | --- |
| `protocol` | так | Має збігатися з `PROTOCOL_VERSION` ketch, зараз `1`. Про розбіжність повідомляється, і плагін ігнорується. |
| `scheme` | так | Префікс, який вводять користувачі: `gitlab:group/project`. Лише латинські літери ASCII, цифри, `-` і `_`. |
| `download` | ні | `true`, якщо плагін сам завантажує асети. |
| `search` | ні | `true`, якщо реалізовано `search`. |

### `releases`

Спершу найновіші. Чернетки слід виключати, а пререлізи включати лише тоді, коли
передано `--prerelease` — ketch однаково повторно застосовує обидва фільтри, тож плагін,
що ігнорує свої прапорці, не може змінити те, що буде встановлено.

```json
[
  {
    "version": "1.2.3",
    "tag": "v1.2.3",
    "prerelease": false,
    "draft": false,
    "published_at": "2026-01-15T10:00:00Z",
    "notes": "release notes",
    "assets": [
      {
        "name": "tool-1.2.3-aarch64-apple-darwin.tar.gz",
        "url": "https://example.invalid/download/tool.tar.gz",
        "size": 2400000,
        "content_type": "application/gzip",
        "digest": { "algo": "sha256", "hex": "e3b0c442..." },
        "headers": { "PRIVATE-TOKEN": "..." }
      }
    ]
  }
]
```

Обов'язкові лише `version`, `tag` і в кожного асета `name` та `url`; усі
інші поля мають типові значення.

Назви асетів тут важливіші за все: ketch обирає, який асет
встановити, оцінюючи **назву** щодо платформи хоста. Повідомляйте назви, які
проєкт справді публікує.

`digest` — це те, що дає ketch змогу перевірити завантаження без зайвих запитів. Вказуйте
його щоразу, коли джерело його знає. Безпосередньо із завантаженням порівнюється лише `sha256`;
інші значення `algo` ігноруються, і ketch переходить до файлів контрольних сум поруч з асетами
або до зведених файлів у релізі.

### `describe` і `search`

Обидві повертають `SourceInfo`:

```json
{
  "id": "group/project",
  "name": "project",
  "description": "one line",
  "homepage": "https://example.invalid",
  "stars": 1200,
  "license": "MIT",
  "archived": false
}
```

`describe` повертає один об'єкт або `null`. `search` повертає масив, порожній, якщо
плагін не підтримує пошук.

### `download`

Викликається лише тоді, коли `capabilities.download` дорівнює `true`. Запишіть асет у `<dest>`
і завершіться з кодом `0`.

Коли `download` дорівнює `false`, ketch сам завантажує `asset.url`, надсилаючи
`asset.headers` і **жодних облікових даних ketch** — URL плагіна ніколи не отримують
GitHub-токен користувача. Покладіть у `headers` будь-які облікові дані, потрібні вашим асетам.

Хай там як, ketch гешує файл, який справді опинився на диску. Плагін не
може сам заявляти контрольну суму свого завантаження.

## Що забезпечує ketch

Плагін — це сторонній виконуваний файл, що запускається від імені користувача, тому:

- **stdin закрито.** Плагін, який намагається щось запитати, отримує EOF, а не
  термінал користувача.
- **вивід обмежено** 8 MiB на потік.
- **діє 30-секундний ліміт** на підкоманду, після якого процес
  примусово завершується.
- **зламаний плагін — це попередження, а не помилка.** Виявлення повідомляє про нього, а
  всі інші джерела працюють далі.

## Мінімальний приклад

```sh
#!/bin/sh
# ketch-source-example — покладіть у ~/.ketch/plugins і виконайте chmod +x
set -eu
case "$1" in
  capabilities)
    printf '{"protocol":1,"scheme":"example","search":false}\n'
    ;;
  describe)
    printf '{"id":"%s","name":"%s"}\n' "$2" "${2##*/}"
    ;;
  releases)
    printf '[{"version":"1.0.0","tag":"v1.0.0","assets":[{"name":"tool-1.0.0-aarch64-apple-darwin.tar.gz","url":"https://example.invalid/tool.tar.gz"}]}]\n'
    ;;
  *)
    echo "unsupported subcommand: $1" >&2
    exit 1
    ;;
esac
```

Потім:

```bash
ketch plugin list
ketch install example:some/project
```
