---
lang: ru
---

# Плагины источников

GitHub встроен. Всё остальное — GitLab, Gitea, внутренний сервер артефактов —
можно добавить плагином, без перекомпиляции ketch и без нового релиза ketch.

Плагин — это исполняемый файл с именем `ketch-source-<scheme>`. ketch запускает его с
подкомандой и читает один JSON-документ из его stdout. Это весь
контракт, поэтому плагин можно написать на любом языке.

Закоммиченный [`ketch-source-example`](../../examples/ketch-source-example) — это
тот же скрипт, что ниже, с правами на исполнение, чтобы его можно было сразу скопировать в
`~/.ketch/plugins` и попробовать через `ketch plugin list`.

## Обнаружение

ketch ищет сначала в `~/.ketch/plugins`, затем во всех каталогах из `PATH`.
Побеждает первый найденный `ketch-source-<name>`, поэтому копия в каталоге плагинов
перекрывает копию из `PATH`. `ketch plugin list` показывает найденное, а `ketch
plugin dir` печатает каталог.

У каждого обнаруженного плагина запрашиваются его возможности при каждой команде ketch, поэтому
`capabilities` должна работать быстро и не обращаться к сети.

## Подкоманды

```text
capabilities              -> {"protocol":1,"scheme":"gitlab","download":false,"search":true}
describe <id>             -> a SourceInfo object, or null
releases <id> [--limit N] [--prerelease]
                          -> [ Release, ... ]
search <query> --limit N  -> [ SourceInfo, ... ]
download <url> <dest>     -> only when capabilities.download is true
```

`KETCH_PROTOCOL_VERSION` задаётся в окружении при каждом вызове.

Завершитесь с кодом `0`, выведя JSON-документ в stdout. Всё остальное считается ошибкой, и
всё, что плагин написал в stderr, показывается пользователю.

### `capabilities`

```json
{ "protocol": 1, "scheme": "gitlab", "download": false, "search": true }
```

| Поле | Обязательно | Значение |
| --- | --- | --- |
| `protocol` | да | Должно совпадать с `PROTOCOL_VERSION` ketch, сейчас `1`. О несовпадении сообщается, и плагин игнорируется. |
| `scheme` | да | Префикс, который вводят пользователи: `gitlab:group/project`. Только латинские буквы ASCII, цифры, `-` и `_`. |
| `download` | нет | `true`, если плагин сам скачивает ассеты. |
| `search` | нет | `true`, если реализован `search`. |

### `releases`

Сначала самые новые. Черновики нужно исключать, а пре-релизы включать только при
переданном `--prerelease` — ketch всё равно повторно применяет оба фильтра, так что плагин,
игнорирующий свои флаги, не может изменить то, что будет установлено.

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

Обязательны только `version`, `tag` и у каждого ассета `name` и `url`; все
остальные поля имеют значения по умолчанию.

Имена ассетов здесь важнее всего: ketch выбирает, какой ассет
установить, оценивая **имя** относительно платформы хоста. Сообщайте имена, которые
проект действительно публикует.

`digest` — это то, что позволяет ketch проверить загрузку без лишних запросов. Указывайте
его всякий раз, когда источник его знает. Напрямую с загрузкой сравнивается только `sha256`;
другие значения `algo` игнорируются, и ketch переходит к файлам контрольных сумм рядом с ассетами
или к сводным файлам в релизе.

### `describe` и `search`

Обе возвращают `SourceInfo`:

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

`describe` возвращает один объект или `null`. `search` возвращает массив, пустой, если
плагин не поддерживает поиск.

### `download`

Вызывается только когда `capabilities.download` равно `true`. Запишите ассет в `<dest>`
и завершитесь с кодом `0`.

Когда `download` равно `false`, ketch сам скачивает `asset.url`, отправляя
`asset.headers` и **никаких учётных данных ketch** — URL плагина никогда не получают
GitHub-токен пользователя. Поместите в `headers` любые учётные данные, нужные вашим ассетам.

В любом случае ketch хеширует файл, который действительно оказался на диске. Плагин не
может сам заявлять контрольную сумму своей загрузки.

## Что обеспечивает ketch

Плагин — это сторонний исполняемый файл, запускаемый от имени пользователя, поэтому:

- **stdin закрыт.** Плагин, который пытается что-то спросить, получает EOF, а не
  терминал пользователя.
- **вывод ограничен** 8 MiB на поток.
- **действует 30-секундный лимит** на подкоманду, после которого процесс
  завершается принудительно.
- **сломанный плагин — это предупреждение, а не ошибка.** Обнаружение сообщает о нём, а
  все остальные источники продолжают работать.

## Минимальный пример

```sh
#!/bin/sh
# ketch-source-example — положите в ~/.ketch/plugins и выполните chmod +x
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

Затем:

```bash
ketch plugin list
ketch install example:some/project
```
