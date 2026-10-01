---
lang: ru
---

# Команды

Все команды ketch в одном месте: что делает каждая, какую форму принимает и один
рабочий пример. `PKG` ниже — установленное имя, алиас или
`owner/repo`; большинство команд, принимающих его, также допускают `@version` в конце
(`sharkdp/fd@v10.2.0`). Глобальные флаги работают везде: `--root <DIR>` указывает на
другое дерево ketch, `-v/--verbose` показывает, что делает ketch, `-q/--quiet`
печатает только ошибки и запрошенные данные, `--no-color` отключает цвет, а
`--no-emoji` убирает значки перед строками статуса.

**Значки.** В терминале каждая строка статуса начинается со значка того, о чём она
сообщает: 📦 установка, ⬆️ обновление пакета или реестра, 🗑️ удаление, ⬇️ загрузка,
🔗 ссылка, ⏪ откат, 🔍 поиск, 🩺 doctor, а в остальных случаях ✅ успех, ⚠️ предупреждение,
❌ ошибка, ℹ️ примечание. По умолчанию они включены (`emoji` в `config.toml`,
`KETCH_EMOJI`) и никогда не появляются в пайпе или файле, при `TERM=dumb`, в
выводе `--json` или `--names-only`, в табличных данных и в логе.

## Установка и удаление

### `ketch install [PKG]... [options]`

Устанавливает один или несколько пакетов. Вывод выбирает ассет релиза, подходящий
хосту (архитектура, ОС, libc); манифест или `--asset` говорят, что делать вместо этого.

```bash
ketch install BurntSushi/ripgrep  # любой репозиторий, публикующий релизы
ketch install rg                  # или имя, известное реестру
ketch install sharkdp/fd@v10.2.0  # или точная версия
ketch install --path ./mytool     # локальный бинарник, архив, симлинк или .app
```

Уже установленный пакет не обновляется у вас за спиной. Если есть
более новый релиз, `install` спрашивает `<pkg> <installed> is installed; update to
<latest>?` (по умолчанию нет) и при ответе «да» обновляет его так же, как `ketch upgrade`;
`--yes` отвечает «да», а без терминала команда останавливается с кодом 5 и предлагает
передать `--yes` или запустить `ketch upgrade`. Если ничего новее нет, она завершается с кодом 5:
`cannot install <pkg>: <version> is already installed and no update is
available` (`--force` переустанавливает). Точная версия (`pkg@1.2.0`) и закреплённый
пакет ведут себя как раньше.

Опции: `--path <PATH>` устанавливает локальный файл (эквивалент
`local:<PATH>`); `--name <NAME>` задаёт имя установки для одного
пакета; `--force/-f` переустанавливает запрошенную версию, даже если она уже есть;
`--pre` учитывает пре-релизы; `--no-link` распаковывает без ссылок в PATH;
`--require-checksum` отклоняет релизы без опубликованной контрольной суммы;
`--asset <NAME>` берёт один файл релиза по точному имени (спрашивает подтверждение,
потому что проверка платформы пропускается); `-j/--jobs <N>` и `-y/--yes` управляют
параллелизмом и вопросами. Алиас — `ketch i`.

Когда релиз содержит несколько бинарников с именем пакета и ни один
манифест не называет нужный, ссылка ставится только на один из них: тот, что назван в `--bin <NAME>`;
иначе — бинарник, названный в точности как пакет; иначе — выбор,
запомненный с прошлого раза (в `state.json`, а при `ketch sync` — в
`ketch.lock`), или выбор по номеру в терминале. Бинарники с другими именами
получают ссылки как обычно. С `--yes` или без терминала выбора нет, и
установка завершается ошибкой со списком кандидатов — см. раздел `bin` в
[MANIFESTS.md](MANIFESTS.md).

`--bin <NAME>` (для одного пакета) отвечает на этот вопрос заранее, поэтому работает
без терминала: `ketch install owner/rtok --bin rtok-cli`. Он сохраняется в
`state.json`, как выбор из списка, и используется при последующих обновлениях. Он завершается ошибкой, если ни у одного бинарника
релиза нет такого имени и если в манифесте пакета уже есть `bin`
(тогда измените его).

### `ketch uninstall <NAME>...`

Удаляет установленные пакеты. Имена разрешаются как в `install` (установленное имя,
бинарник или `owner/repo`); опечатка останавливает команду до того, как что-либо будет удалено.
Удаляется вся папка пакета в хранилище, включая всё, что там оставило прерванное
обновление.
Для неустановленного имени печатается `<name>: not found` и возвращается код 4; перечисляется каждое
отсутствующее имя, и ничего не удаляется.

```bash
ketch uninstall rg
ketch uninstall fd ripgrep --yes  # пропустить подтверждение
```

Алиасы — `ketch remove` и `ketch rm`.

### `ketch upgrade [NAME]...`

Обновляет установленные пакеты до последнего релиза. Без аргументов — все
незакреплённые пакеты. Показывает таблицу `from -> to`, спрашивает, затем устанавливает.

Каждая версия распаковывается в собственную новую папку, так что ничто из поставки старой
версии не может задержаться в новой, а остатки прерванного
обновления сначала удаляются — или обновление останавливается и называет их. Папка предыдущей
версии хранится рядом для `ketch rollback` до `ketch prune`.

```bash
ketch upgrade              # всё незакреплённое
ketch upgrade ripgrep fd   # только эти
ketch upgrade --dry-run    # показать план, ничего не меняя
```

Опции: `--pre` учитывает пре-релизы; `--force` обновляет и закреплённые
пакеты; `--bin <NAME>` (ровно для одного пакета) выбирает, на какой бинарник ставить ссылку, когда
новый релиз содержит несколько файлов с именем пакета, как в `install`;
`-j/--jobs <N>`, `-y/--yes`. Закреплённые пакеты и пакеты `local:` пропускаются, если
не указан `--force`.

### `ketch rollback <PKG> [--to <VERSION>]`

Переключает пакет обратно на версию, которая ещё лежит на диске. Без `--to` восстанавливает
предыдущую сохранённую версию.

```bash
ketch rollback ripgrep
ketch rollback rg --to 14.1.0
```

### `ketch prune [NAME]... [--keep <N>]`

Удаляет сохранённые старые версии согласно политике хранения. Без аргументов — все
установленные пакеты. `--keep <N>` обновляет сохранённую политику.

```bash
ketch prune
ketch prune ripgrep --keep 2
```

### `ketch pin <NAME>...` / `ketch unpin <NAME>...`

Закрепляет пакет на текущей версии (`pin`) или снимает закрепление (`unpin`).
Закреплённые пакеты пропускаются командой `upgrade`, если не передан `--force`.

```bash
ketch pin ripgrep
ketch upgrade --force        # обновляет и закреплённые пакеты
ketch unpin ripgrep
```

### `ketch link <NAME>...` / `ketch unlink <NAME>...`

Заново создаёт (`link`) или удаляет (`unlink`) ссылки в каталоге bin для установленного
пакета. `unlink` оставляет содержимое установленным; `upgrade` сохраняет состояние
ссылок.

```bash
ketch unlink ripgrep   # оставить, но убрать из PATH
ketch link ripgrep     # вернуть обратно
```
## Просмотр

### `ketch list`

`ketch list [local|remote] [--json] [--names-only]`, алиас — `ketch ls`.

Показывает, что установлено, что предлагает реестр, или и то и другое в одной таблице с
самой новой версией каждого пакета.

| Команда | Показывает | Сеть |
| --- | --- | --- |
| `ketch list local` | Установленные пакеты из записи об установке | Никогда |
| `ketch list remote` | Пакеты, которые предлагает реестр, с последней версией | Нужна; без неё ошибка |
| `ketch list` | И то и другое, по строке на пакет, с сортировкой по имени | Используется для `latest`; без неё — локальная часть |

«Реестр» здесь — это локальная копия, которую загружает `ketch update`, плюс ваши собственные
манифесты в `~/.ketch/manifests` (ваши побеждают, если имя есть и там и там). Те немногие
пакеты, что вкомпилированы в ketch как запасной вариант, не перечисляются; запустите `ketch update`,
чтобы увидеть реестр, из которого они взяты.

**Столбцы.**

- `package` — имя, по которому пакет устанавливают, обновляют или удаляют.
- `installed` — установленная версия. `(pinned)` означает, что его удерживает `ketch pin`;
  `(+N retained)` означает, что N более ранних версий сохранены для `ketch rollback`.
  Пусто для неустановленного пакета.
- `latest` — самый новый релиз источника пакета. За ним следует `(update available)`,
  если это обновление для установленной версии. `?` означает, что
  источник не ответил. Пусто для пакета, установленного из локального пути,
  у которого нет upstream, чтобы спросить.
- `source` — откуда берётся пакет. Для установленного пакета это
  то, откуда он был установлен и откуда придёт его следующая версия.
- `description` (только `remote`) — однострочное описание из реестра, обрезанное по
  ширине терминала (`COLUMNS` задаёт другую ширину; при выводе в пайп
  ничего не обрезается).

**Маркеры.** В `ketch list` у установленного пакета в первом столбце стоит `*`.
В цветном терминале маркер — `●`, имя выделено жирным, а
`(update available)` — жёлтым. `CLICOLOR_FORCE=1` включает цвет даже при выводе в
пайп; `--no-color` и `NO_COLOR` выключают его.

**Наличие обновления** определяется так же, как в `ketch outdated`. `latest` —
самый новый релиз, о котором сообщает источник пакета, из того же запроса, что делает
`ketch outdated`: только стабильные релизы, если в `config.toml` или в манифесте пакета не задано
`prerelease = true`. Эта версия сравнивается
с установленной, и учитывается только строго более новая; релиз с
установленным тегом — никогда. Закреплённому пакету обновление никогда не предлагается.

**Закреплённые пакеты** перечисляются с обеими версиями и `(pinned)`, не получают
`(update available)` и не попадают в итоговую строку. `ketch unpin` снова позволяет им
обновляться.

**Пакеты не из реестра** — установленные из `owner/repo` или
другой ссылки `scheme:id` — тоже перечисляются, и их `latest` берётся из
их собственного источника. Пакет, установленный из локального пути (`ketch install --path`),
показывается с пустым `latest`.

Под таблицей строка `N updates available: ketch upgrade <names>` называет каждый
пакет с обновлением в виде команды, которая обновит их все.

**Офлайн и пакеты, которые не отвечают.** Последние версии запрашиваются
параллельно (по `jobs` за раз, по умолчанию 4), а пока они загружаются, в stderr идёт счётчик.
Источник, который не ответил, — упёрся в лимит, исчез, недоступен — показывает `?` в
`latest`, а под таблицей одна строка называет каждый такой пакет; все остальные строки
всё равно печатаются, и команда всё равно завершается успешно. Когда не отвечает ни один источник,
считается, что сети нет: `ketch list` печатает таблицу `ketch list local`,
за ней `latest: offline`, и возвращает 0, а `ketch list remote`,
которому без сети нечего показать, возвращает ненулевой код.

Ответы кешируются на 10 минут в `~/.ketch/cache/latest.json` для каждого источника
и каждой настройки пре-релизов, так что два вызова подряд обращаются к GitHub один раз. Кешируются только
ответы, но не сбои. Удалите файл, чтобы спросить заново раньше;
`ketch outdated` и `ketch upgrade` его никогда не читают.

**`--json`** печатает для каждого режима:

- `local` — массив `{"name", "installed", "pinned", "retained", "source"}`,
  где `retained` — список версий, сохранённых для отката.
- `remote` — массив `{"name", "latest", "description", "source"}`, где
  `latest` равно `null` для источника, который не ответил.
- без режима — `{"packages": [...], "unreachable": [...]}`. Каждый пакет —
  `{"name", "installed", "latest", "update_available", "pinned", "source"}`;
  `installed` равно `null`, если пакет не установлен, а `latest` равно `null`, если
  версия неизвестна. `unreachable` называет пакеты, чей `latest` неизвестен.
  Офлайн `packages` содержит только установленные пакеты.

**`--names-only`** печатает только имена, по одному на строку, для каждого режима; к сети он никогда
не обращается.

**Изменено в 0.7.** Раньше `ketch list` показывал только установленные пакеты, а его
`--json` был массивом записей об установке; теперь он показывает всё в
описанном выше виде. Скриптам, которым нужны установленные пакеты, следует вызывать
`ketch list local`. `ketch list --installed` — скрытый алиас
`ketch list local`, сохранённый на один релиз.

```text
$ ketch list local
package  installed        source
fd       v10.4.2          github:sharkdp/fd
ripgrep  14.1.1           github:BurntSushi/ripgrep
rtok     v0.9.0 (pinned)  github:pyrlyn/rtok
```

```text
$ ketch list remote
package  latest   description
cox      v0.1.0   Modular terminal coding agent
dunnage  v0.1.0   Shrink Cargo target directories without slowing builds
ketch    v0.6.1   Catch releases straight from GitHub
ripgrep  15.2.0   Recursively search directories for a regex pattern
rtok     v0.10.0  Reduce the context AI coding agents must carry
runa     ?        Run AI models locally (GGUF via llama.cpp) or through the OpenAI and Anthropic APIs
? means the latest release could not be checked: runa
```

```text
$ ketch list
   package  installed        latest                      source
   cox                       v0.1.0                      github:pyrlyn/cox
   dunnage                   v0.1.0                      github:listepo/dunnage
*  fd       v10.4.2          v10.5.0 (update available)  github:sharkdp/fd
   ketch                     v0.6.1                      github:pyrlyn/ketch
*  ripgrep  14.1.1           15.2.0 (update available)   github:BurntSushi/ripgrep
*  rtok     v0.9.0 (pinned)  v0.10.0                     github:pyrlyn/rtok
   runa                      ?                           github:pyrlyn/runa
2 updates available: ketch upgrade fd ripgrep
? means the latest release could not be checked: runa
```

Без сети:

```text
$ ketch list
package  installed        source
fd       v10.4.2          github:sharkdp/fd
ripgrep  14.1.1           github:BurntSushi/ripgrep
rtok     v0.9.0 (pinned)  github:pyrlyn/rtok
latest: offline
$ ketch list remote
     error could not reach any package source to check the latest versions; `ketch list local` works offline
```

```bash
ketch list local --json      # установленные пакеты, для скриптов
ketch list --names-only      # все имена пакетов, по одному на строку
ketch list remote --json     # реестр с последними версиями
```

### `ketch outdated [--json] [--pre]`

Показывает установленные пакеты, у которых есть более новый релиз. Недоступный источник —
это предупреждение, а не ошибка, если только вообще ничего не удалось проверить.

```bash
ketch outdated
ketch outdated --json   # {"status","outdated","failed","unreachable"}
```

### `ketch info <PKG> [--assets] [--json]`

Показывает подробности о пакете, установленном или нет: источник, описание, последнюю
и установленную версии, ссылки, хранение, проверку.

```bash
ketch info BurntSushi/ripgrep
ketch info rg --assets   # каждый ассет релиза с оценкой и её причиной
```

Алиас — `ketch show`.

### `ketch why <PKG> [--json]`

Объясняет, как был бы разрешён пакет, не устанавливая его: какой уровень манифестов
ответил, какой релиз и ассет были выбраны и почему.

```bash
ketch why sharkdp/fd@v10.2.0
ketch why rg --json
```

### `ketch changelog <PKG> [--latest] [--file] [--release]`

Показывает, что изменилось: файл changelog, который поставляется с пакетом, или заметки к релизу,
опубликованные вместе с ним. Для установленной версии предпочитает файл на диске;
иначе использует заметки.

```bash
ketch changelog ripgrep            # установленная версия
ketch changelog ripgrep --latest   # вместо неё самый новый релиз
ketch changelog ripgrep --file     # только поставляемый файл
ketch changelog ripgrep --release  # только опубликованные заметки
```

### `ketch search <QUERY>... [-n <LIMIT>]`

Ищет на GitHub (и в плагинах с поиском) репозитории, которые можно установить.

```bash
ketch search fuzzy finder
ketch search ripgrep -n 5
```

## Воспроизведение

### `ketch lock [--file <FILE>] [--check]`

Записывает `./ketch.lock` по тому, что установлено, — воспроизводимую запись
утилит машины, закреплённых на точных релизах. См. [LOCKFILE.md](LOCKFILE.md).

```bash
ketch lock              # записать ./ketch.lock
ketch lock --check      # не разошлось ли дерево с ним?
ketch lock -f tools.lock
```

### `ketch sync [--file <FILE>] [--prune] [--dry-run]`

Устанавливает всё, что названо в `ketch.lock`, в названных там версиях. Пакет, не
упомянутый в lock-файле, не трогается, если не передан `--prune` (который сначала спрашивает,
потому что может уничтожить чью-то работу).

```bash
ketch sync                 # только отсутствующие или разошедшиеся пакеты
ketch sync --dry-run       # показать план +/~/−, ничего не меняя
ketch sync --prune --yes   # также удалить лишнее, не спрашивая
```

## История

### `ketch history [PKG] [-n <LIMIT>] [--json]`

Показывает, что устанавливалось, обновлялось и удалялось, начиная с самого нового. Без пакета
показывает всё дерево на одной временной шкале.

```bash
ketch history
ketch history ripgrep -n 10
```

### `ketch stats [--json]`

Сводит всё, что ketch записал в `stats.db`.

```bash
ketch stats
ketch stats --json
```

## Реестр и источники

### `ketch update`

Обновляет реестр пакетов в `~/.ketch/registry`. (Для установленных
пакетов см. `upgrade`.)

```bash
ketch update
```

Запускается автоматически в начале `install` и `upgrade`, если
`auto_update` не равно `false` в `~/.ketch/config.toml`
(`KETCH_AUTO_UPDATE=false`).

### `ketch registry validate [DIR] [--fixture <DIR>] [--changed <NAME>] [--json]`

Проверяет дерево реестра так же, как pre-push хук: каждый `ketch.toml` разобран и
проверен, плюс конфликты имён. `--fixture` в качестве дополнительного доказательства выполняет офлайн-установку записей из
локальных файлов.

```bash
ketch registry validate
ketch registry validate ./ketch-registry --fixture ./fixtures
```

### `ketch registry status [--json]`

Показывает возраст и источник локальной копии реестра, ничего не загружая.

```bash
ketch registry status
```

### `ketch registry push [--file <FILE>] [--registry <REPO>] [--dry-run] [--yes]`

Сравнивает `ketch.toml` этого проекта с копией в реестре и открывает с ним пул-реквест.
Для нового пакета открывает сразу; для обновления показывает дифф и спрашивает;
если файлы идентичны, сообщает `unchanged` и ничего не открывает. См.
[REGISTRY.md](REGISTRY.md).

```bash
KETCH_GITHUB_TOKEN=... ketch registry push
ketch registry push --dry-run
```

### `ketch plugin list [--json]` / `ketch plugin dir`

Показывает обнаруженные плагины источников (`ketch-source-<scheme>` в `~/.ketch/plugins`
и в `PATH`) или печатает каталог плагинов. См. [PLUGINS.md](PLUGINS.md).

```bash
ketch plugin list
ketch plugin dir
```

## Окружение

### `ketch doctor [--fix] [--json]`

Проверяет окружение и дерево установки: версию, настройку PATH, проверки
платформы, лог, возраст реестра, соответствие хранилища `state.json`. Возвращает ненулевой код, если
проверка не прошла. В Windows также предупреждает о записях в пользовательском PATH, указывающих на каталог bin
ketch, папки которого уже нет, — этого корня или любой `.ketch\bin`.

```bash
ketch doctor
ketch doctor --fix    # исправить то, что можно исправить (настройку PATH)
```

### `ketch path [install|uninstall|status]`

Добавляет каталог bin ketch в PATH. Просто `ketch path` показывает таблицу
состояния; это подкоманда по умолчанию.

```bash
ketch path                  # каталог bin, состояние PATH, таблица по оболочкам
ketch path install          # изменить файлы запуска оболочек (спрашивает для каждой)
ketch path install --print  # напечатать строку, чтобы добавить её вручную
ketch path install --all    # действовать для всех известных оболочек
ketch path uninstall        # снова убрать блок
```

### `ketch config create [--file <FILE>] [--force] [--yes]`

Записывает конфигурацию пакета (`ketch.toml`) по ответам на вопросы — источник, имя,
записи bin, шаблоны ассетов, — затем показывает и записывает файл. Пакет, который
ставит ссылки на бинарники, должен называть команду, которую добавляет в PATH, поэтому первую запись `bin`
команда запрашивает, а не предлагает; её имя по умолчанию — имя пакета.
Ответы можно передать через stdin, по одному на строку. См. [MANIFESTS.md](MANIFESTS.md).

```bash
ketch config create
ketch config create --file ./ketch.toml --yes
```

### `ketch config reset [--yes]`

Записывает `config.toml` в корне ketch со скомпилированными значениями по умолчанию. Сначала спрашивает,
если не передан `--yes`; сохраняет резервную копию существующего файла рядом с ним как
`config.toml.bak-<unix-seconds>`, если файл есть и не совпадает с уже
лежащей рядом резервной копией.

```bash
ketch config reset
ketch config reset --yes
```

### `ketch completions <SHELL> [--install]`

Печатает скрипт автодополнения для оболочки или с `--install` устанавливает его в
пользовательский каталог автодополнений оболочки.

```bash
ketch completions zsh > _ketch
ketch completions bash --install
```

Скрипты для bash и PowerShell также дополняют имена пакетов: установленных — после
`uninstall`, `upgrade`, `pin`, `unpin`, `link`, `unlink`, `info`, `why`,
`changelog` и `rollback`, и имена из локальной копии реестра — после
`install` и `search`. Скрипт запрашивает их у бинарника внутренней командой
`ketch __complete <installed|registry> [PREFIX]`, которая читает файл состояния и
уже лежащий на диске реестр и никогда не обращается к сети. `--root`, указанный ранее
в командной строке, учитывается.

`--install` записывает скрипт bash в
`${XDG_DATA_HOME:-~/.local/share}/bash-completion/completions/ketch`, откуда
bash-completion 2 загружает его при первом использовании. В macOS для этого системной
оболочки мало: `/bin/bash` — это 3.2, а bash-completion 2 нужен bash 4.2 или новее.
Установите свежий bash и bash-completion 2 (через Homebrew:
`brew install bash bash-completion@2`), сделайте этот bash оболочкой входа и
подключайте `bash_completion` из bash-completion в `~/.bashrc`, как сказано в его
примечаниях. Сам скрипт работает и под bash
3.2, так что `eval "$(ketch completions bash)"` в `~/.bashrc` работает и без них.

В Windows `--install` (и `ketch self install`, который устанавливает скрипты для всех
оболочек) также включает автодополнение, потому что ни одна из оболочек не загружает
каталог автодополнений сама:

- **PowerShell.** Скрипт помещается в `Documents\PowerShell\Completions\ketch.ps1`,
  а блок между `# >>> ketch >>>` и `# <<< ketch <<<` в профиле
  CurrentUserAllHosts для PowerShell 7 (`Documents\PowerShell\profile.ps1`)
  и Windows PowerShell 5.1 (`Documents\WindowsPowerShell\profile.ps1`)
  подключает его через dot-sourcing. Documents — это папка, о которой сообщает PowerShell, так что перенаправление
  в OneDrive учитывается. Ещё не существующий профиль создаётся, только если
  эта редакция установлена и её политика выполнения разрешает локальные скрипты;
  иначе ketch объясняет, почему не стал его трогать.
- **cmd.** В cmd нет программируемого автодополнения, поэтому он получает макросы doskey:
  `ki` (`ketch install`), `ku` (`ketch upgrade`), `kl` (`ketch list`) и `kun`
  (`ketch uninstall`), каждый из которых передаёт свои аргументы дальше. Они лежат в
  `<root>\share\ketch\ketch.doskey` и загружаются командой
  `doskey /macrofile="…"`, добавленной в
  `HKCU\Software\Microsoft\Command Processor\AutoRun` через ` & ` после
  того, что AutoRun уже выполняет.

`ketch self uninstall` убирает блоки из профилей (удаляя профиль, в котором
больше ничего не было) и удаляет из AutoRun ровно свою команду, оставляя
прежнее значение как было или удаляя значение, если в нём была только команда ketch.

## Сам ketch

### `ketch self install [--force] [--link-dir <DIR>]`

Устанавливает этот релиз ketch как пакет — в хранилище и каталог bin. Именно это
запускают установщики curl/PowerShell/Homebrew.

```bash
ketch self install
```

### `ketch self upgrade [--dry-run] [--force] [--yes]`

Обновляет ketch до последнего релиза. Алиас — `ketch self update`. ketch,
установленный через mise и ни разу не установленный через `self install`, обновляет mise:
команда отказывается и называет `mise upgrade`.

```bash
ketch self upgrade
ketch self upgrade --dry-run
```

### `ketch self version`

Печатает запущенную версию и где она находится (target, корень, бинарник, PATH).

```bash
ketch self version
```

### `ketch self uninstall [--keep-packages] [--dry-run] [--yes]`

Удаляет ketch и всё, что он установил, безвозвратно. Перечисляет, что собирается
удалить, и сначала спрашивает. `--keep-packages` удаляет только ketch. Для ketch,
установленного через mise, также спрашивает, запустить ли `mise unuse -g` для его собственной копии;
`--yes` отвечает и на это.
В Windows запущенный `ketch.exe` не может удалить сам себя, поэтому остальная часть
корня удаляется фоновым процессом после выхода ketch.

В Windows он также удаляет то, что ketch записал в реестр Windows. Сейчас это
только каталог bin в пользовательском PATH (`HKCU\Environment\Path`), записанный
`install.ps1` или `ketch path install`. Запись находится как бы она ни была
написана: регистр, кавычки, `/` или `\`, завершающий разделитель или короткое имя
8.3. `--keep-packages` оставляет её, как и блоки в оболочках, потому что
она нужна пакетам, которые остаются в каталоге bin. ketch никогда не регистрирует себя в «Приложениях и
возможностях», так что удалять там нечего.

Он также удаляет блоки из профилей PowerShell и добавку в AutoRun cmd,
которые включают автодополнение, — их и с `--keep-packages`, потому что оба
загружают сам ketch.

```bash
ketch self uninstall --dry-run
ketch self uninstall --keep-packages
```
