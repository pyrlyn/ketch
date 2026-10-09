---
lang: uk
---

# Команди

Усі команди ketch в одному місці: що робить кожна, яку форму приймає та один
робочий приклад. `PKG` нижче — встановлена назва, аліас або
`owner/repo`; більшість команд, що його приймають, також допускають `@version` у кінці
(`sharkdp/fd@v10.2.0`). Глобальні прапорці працюють скрізь: `--root <DIR>` вказує на
інше дерево ketch, `-v/--verbose` показує, що робить ketch, `-q/--quiet`
друкує лише помилки й запитані дані, `--no-color` вимикає колір, а
`--no-emoji` прибирає значки перед рядками статусу.

**Значки.** У терміналі кожен рядок статусу починається зі значка того, про що він
повідомляє: 📦 встановлення, ⏫ оновлення пакета чи реєстру, 🧹 видалення, ⏬ завантаження,
🔗 посилання, ⏪ відкат, 🔍 пошук, 🩺 doctor, а в решті випадків ✅ успіх, ❗ попередження,
❌ помилка, 💡 примітка. Типово вони ввімкнені (`emoji` у `config.toml`,
`KETCH_EMOJI`) і ніколи не з'являються в пайпі чи файлі, за `TERM=dumb`, у
виводі `--json` або `--names-only`, у табличних даних і в лозі.

## Встановлення та видалення

### `ketch install [PKG]... [options]`

Встановлює один або кілька пакетів. Виведення обирає асет релізу, що пасує
хосту (архітектура, ОС, libc); маніфест або `--asset` кажуть, що робити натомість.

```bash
ketch install BurntSushi/ripgrep  # будь-який репозиторій, що публікує релізи
ketch install rg                  # або назва, відома реєстру
ketch install sharkdp/fd@v10.2.0  # або точна версія
ketch install --path ./mytool     # локальний бінарник, архів, символьне посилання або .app
```

Уже встановлений пакет не оновлюється у вас за спиною. Якщо є
новіший реліз, `install` питає `<pkg> <installed> is installed; update to
<latest>?` (типово ні) і в разі відповіді «так» оновлює його так само, як `ketch upgrade`;
`--yes` відповідає «так», а без термінала команда зупиняється з кодом 5 і пропонує
передати `--yes` або запустити `ketch upgrade`. Якщо нічого новішого немає, вона завершується з кодом 5:
`cannot install <pkg>: <version> is already installed and no update is
available` (`--force` перевстановлює). Точна версія (`pkg@1.2.0`) і закріплений
пакет поводяться як раніше.

Опції: `--path <PATH>` встановлює локальний файл (еквівалент
`local:<PATH>`); `--name <NAME>` задає назву встановлення для одного
пакета; `--force/-f` перевстановлює запитану версію, навіть якщо вона вже є;
`--pre` враховує пререлізи; `--no-link` розпаковує без посилань у PATH;
`--require-checksum` відхиляє релізи без опублікованої контрольної суми;
`--asset <NAME>` бере один файл релізу за точною назвою (питає підтвердження,
бо перевірка платформи пропускається); `-j/--jobs <N>` і `-y/--yes` керують
паралелізмом і запитаннями. Аліас — `ketch i`.

Коли реліз містить кілька бінарників із назвою пакета й жоден
маніфест не називає потрібний, посилання ставиться лише на один із них: той, що названий у `--bin <NAME>`;
інакше — бінарник, названий точно як пакет; інакше — вибір,
запам'ятований з минулого разу (у `state.json`, а під час `ketch sync` — у
`ketch.lock`), або вибір за номером у терміналі. Бінарники з іншими назвами
отримують посилання як звичайно. З `--yes` або без термінала вибору немає, і
встановлення завершується помилкою зі списком кандидатів — див. розділ `bin` у
[MANIFESTS.md](MANIFESTS.md).

`--bin <NAME>` (для одного пакета) відповідає на це запитання заздалегідь, тому працює
без термінала: `ketch install owner/rtok --bin rtok-cli`. Він зберігається в
`state.json`, як вибір зі списку, і використовується під час подальших оновлень. Він завершується помилкою, якщо жоден бінарник
релізу не має такої назви і якщо в маніфесті пакета вже є `bin`
(тоді змініть його).

### `ketch uninstall <NAME>...`

Видаляє встановлені пакети. Назви розв'язуються як в `install` (встановлена назва,
бінарник або `owner/repo`); одруківка зупиняє команду до того, як щось буде видалено.
Видаляється вся тека пакета в сховищі, разом з усім, що там залишило перерване
оновлення.
Для невстановленої назви друкується `<name>: not found` і повертається код 4; перелічується кожна
відсутня назва, і нічого не видаляється.

```bash
ketch uninstall rg
ketch uninstall fd ripgrep --yes  # пропустити підтвердження
```

Аліаси — `ketch remove` і `ketch rm`.

### `ketch upgrade [NAME]...`

Оновлює встановлені пакети до останнього релізу. Без аргументів — усі
незакріплені пакети. Показує таблицю `from -> to`, питає, потім встановлює.

Кожна версія розпаковується у власну нову теку, тож ніщо з постачання старої
версії не може затриматися в новій, а залишки перерваного
оновлення спершу видаляються — або оновлення зупиняється й називає їх. Тека попередньої
версії зберігається поруч для `ketch rollback` до `ketch prune`.

```bash
ketch upgrade              # усе незакріплене
ketch upgrade ripgrep fd   # лише ці
ketch upgrade --dry-run    # показати план, нічого не змінюючи
```

Опції: `--pre` враховує пререлізи; `--force` оновлює й закріплені
пакети; `--bin <NAME>` (рівно для одного пакета) обирає, на який бінарник ставити посилання, коли
новий реліз містить кілька файлів із назвою пакета, як в `install`;
`-j/--jobs <N>`, `-y/--yes`. Закріплені пакети й пакети `local:` пропускаються, якщо
не вказано `--force`.

### `ketch rollback <PKG> [--to <VERSION>]`

Перемикає пакет назад на версію, яка ще лежить на диску. Без `--to` відновлює
попередню збережену версію.

```bash
ketch rollback ripgrep
ketch rollback rg --to 14.1.0
```

### `ketch prune [NAME]... [--keep <N>]`

Видаляє збережені старі версії згідно з політикою зберігання. Без аргументів — усі
встановлені пакети. `--keep <N>` оновлює збережену політику.

```bash
ketch prune
ketch prune ripgrep --keep 2
```

### `ketch pin <NAME>...` / `ketch unpin <NAME>...`

Закріплює пакет на поточній версії (`pin`) або знімає закріплення (`unpin`).
Закріплені пакети пропускаються командою `upgrade`, якщо не передано `--force`.

```bash
ketch pin ripgrep
ketch upgrade --force        # оновлює й закріплені пакети
ketch unpin ripgrep
```

### `ketch link <NAME>...` / `ketch unlink <NAME>...`

Наново створює (`link`) або видаляє (`unlink`) посилання в каталозі bin для встановленого
пакета. `unlink` лишає вміст встановленим; `upgrade` зберігає стан
посилань.

```bash
ketch unlink ripgrep   # лишити, але прибрати з PATH
ketch link ripgrep     # повернути назад
```

### `ketch import winget|brew|linux <NAME> [--dry-run] [--yes]`

Додає пакет, який уже знає інший пакетний менеджер. ketch читає його
опис, перетворює його на користувацький маніфест
`~/.ketch/manifests/<name>.toml` і встановлює звичайним шляхом, з ассетом і
контрольною сумою з цього опису.

```bash
ketch import brew codex                    # cask Homebrew (або formula)
ketch import brew fly --cask               # шукати лише cask; --formula — лише formula
ketch import winget BurntSushi.ripgrep.MSVC  # ідентифікатор winget, з урахуванням регістру
ketch import linux lazygit                 # Arch Linux, потім AUR
ketch import linux obsidian --dry-run      # надрукувати маніфест, нічого не змінюючи
```

| Джерело | Звідки береться опис |
| --- | --- |
| `winget` | маніфест інсталятора найновішої версії в [winget-pkgs](https://github.com/microsoft/winget-pkgs) |
| `brew` | JSON cask або formula з [formulae.brew.sh](https://formulae.brew.sh/docs/api/) |
| `linux` | `.SRCINFO` пакета [Arch Linux](https://archlinux.org/packages/), або пакета AUR із суфіксом `-bin`/`-appimage`, якщо Arch збирає його з джерельних кодів |

Перетворюється лише пакет, чиї завантаження — ассети релізу GitHub
(`https://github.com/<owner>/<repo>/releases/download/...`). Завантаження звідкись
іще — CDN вендора, SourceForge, джерельний tarball, домашня сторінка GitHub із
файлами в іншому місці або суміш — нічого не записує і завершується з кодом 1
і повідомленням
`<name> can't be converted: it is not distributed through GitHub Releases, and that is not supported yet.`
Так само й інсталятори, які ketch не вміє запускати (`.msi`, `.msix`, Inno або
NSIS `.exe`, `.deb`, `.rpm`), артефакти cask окрім застосунку і бінарників
(`pkg`, `installer`) і два файли на одну платформу.

Маніфест зберігає лише те, що потрібно ketch: `name`, `source`, `kind` для
застосунку, `bin` і по одному шаблону `[asset.target]` на платформу, де версію
замінено на `*`, щоб шаблон пасував і далі. Він починається з рядка
``# Written by `ketch import …` ``; файл із таким ім'ям без цього рядка — ваш,
і import відмовляється його замінювати.

Повторний запуск безпечний: якщо перетворений маніфест і встановлена
версія вже збігаються з джерелом, друкується
`Everything is up to date` і нічого не змінюється. Нова версія вище за течією
переписує файл і оновлює пакет; змінений маніфест за тієї самої версії
перевстановлює його.

Адреси каталогів можна перенаправити (дзеркало або тестовий двійник) через
`KETCH_IMPORT_BREW`, `KETCH_IMPORT_WINGET_API`, `KETCH_IMPORT_WINGET_RAW`,
`KETCH_IMPORT_ARCH`, `KETCH_IMPORT_ARCH_GITLAB` і `KETCH_IMPORT_AUR`. Список
winget іде через GitHub API, тому використовується токен GitHub, якщо його задано.

## Перегляд

### `ketch list`

`ketch list [local|remote] [--json] [--names-only]`, аліас — `ketch ls`.

Показує, що встановлено, що пропонує реєстр, або і те, і те в одній таблиці з
найновішою версією кожного пакета.

| Команда | Показує | Мережа |
| --- | --- | --- |
| `ketch list local` | Встановлені пакети із запису про встановлення | Ніколи |
| `ketch list remote` | Пакети, які пропонує реєстр, з останньою версією | Потрібна; без неї помилка |
| `ketch list` | І те, і те, по рядку на пакет, із сортуванням за назвою | Використовується для `latest`; без неї — локальна частина |

«Реєстр» тут — це локальна копія, яку завантажує `ketch update`, плюс ваші власні
маніфести в `~/.ketch/manifests` (ваші перемагають, якщо назва є і там, і там). Ті нечисленні
пакети, що вкомпільовані в ketch як запасний варіант, не перелічуються; запустіть `ketch update`,
щоб побачити реєстр, з якого їх узято.

**Стовпці.**

- `package` — назва, за якою пакет встановлюють, оновлюють або видаляють.
- `installed` — встановлена версія. `(pinned)` означає, що його утримує `ketch pin`;
  `(+N retained)` означає, що N попередніх версій збережено для `ketch rollback`.
  Порожньо для невстановленого пакета.
- `latest` — найновіший реліз джерела пакета. За ним іде `(update available)`,
  якщо це оновлення для встановленої версії. `?` означає, що
  джерело не відповіло. Порожньо для пакета, встановленого з локального шляху,
  який не має upstream, щоб запитати.
- `source` — звідки береться пакет. Для встановленого пакета це
  те, звідки його встановлено й звідки прийде його наступна версія.
- `description` (лише `remote`) — однорядковий опис із реєстру, обрізаний за
  шириною термінала (`COLUMNS` задає іншу ширину; під час виводу в пайп
  нічого не обрізається).

**Маркери.** У `ketch list` у встановленого пакета в першому стовпці стоїть `*`.
У кольоровому терміналі маркер — `●`, назва виділена жирним, а
`(update available)` — жовтим. `CLICOLOR_FORCE=1` вмикає колір навіть під час виводу в
пайп; `--no-color` і `NO_COLOR` вимикають його.

**Наявність оновлення** визначається так само, як у `ketch outdated`. `latest` —
найновіший реліз, про який повідомляє джерело пакета, з того самого запиту, що робить
`ketch outdated`: лише стабільні релізи, якщо в `config.toml` або в маніфесті пакета не задано
`prerelease = true`. Ця версія порівнюється
зі встановленою, і враховується лише строго новіша; реліз зі
встановленим тегом — ніколи. Закріпленому пакету оновлення ніколи не пропонується.

**Закріплені пакети** перелічуються з обома версіями та `(pinned)`, не отримують
`(update available)` і не потрапляють у підсумковий рядок. `ketch unpin` знову дозволяє їм
оновлюватися.

**Пакети не з реєстру** — встановлені з `owner/repo` або
іншого посилання `scheme:id` — теж перелічуються, і їхній `latest` береться з
їхнього власного джерела. Пакет, встановлений з локального шляху (`ketch install --path`),
показується з порожнім `latest`.

Під таблицею рядок `N updates available: ketch upgrade <names>` називає кожен
пакет з оновленням у вигляді команди, яка оновить їх усі.

**Офлайн і пакети, що не відповідають.** Останні версії запитуються
паралельно (по `jobs` за раз, типово 4), а поки вони завантажуються, у stderr іде лічильник.
Джерело, що не відповіло, — уперлося в ліміт, зникло, недоступне — показує `?` у
`latest`, а під таблицею один рядок називає кожен такий пакет; усі інші рядки
однаково друкуються, і команда однаково завершується успішно. Коли не відповідає жодне джерело,
вважається, що мережі немає: `ketch list` друкує таблицю `ketch list local`,
за нею `latest: offline`, і повертає 0, а `ketch list remote`,
якому без мережі нічого показати, повертає ненульовий код.

Відповіді кешуються на 10 хвилин у `~/.ketch/cache/latest.json` для кожного джерела
й кожного налаштування пререлізів, тож два виклики поспіль звертаються до GitHub один раз. Кешуються лише
відповіді, але не збої. Видаліть файл, щоб запитати наново раніше;
`ketch outdated` і `ketch upgrade` його ніколи не читають.

**`--json`** друкує для кожного режиму:

- `local` — масив `{"name", "installed", "pinned", "retained", "source"}`,
  де `retained` — список версій, збережених для відкату.
- `remote` — масив `{"name", "latest", "description", "source"}`, де
  `latest` дорівнює `null` для джерела, що не відповіло.
- без режиму — `{"packages": [...], "unreachable": [...]}`. Кожен пакет —
  `{"name", "installed", "latest", "update_available", "pinned", "source"}`;
  `installed` дорівнює `null`, якщо пакет не встановлено, а `latest` дорівнює `null`, якщо
  версія невідома. `unreachable` називає пакети, чий `latest` невідомий.
  Офлайн `packages` містить лише встановлені пакети.

**`--names-only`** друкує лише назви, по одній на рядок, для кожного режиму; до мережі він ніколи
не звертається.

**Змінено в 0.7.** Раніше `ketch list` показував лише встановлені пакети, а його
`--json` був масивом записів про встановлення; тепер він показує все в
описаному вище вигляді. Скриптам, яким потрібні встановлені пакети, слід викликати
`ketch list local`. `ketch list --installed` — прихований аліас
`ketch list local`, збережений на один реліз.

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
ketch    v0.6.1   Catch releases straight from GitHub
ripgrep  15.2.0   Recursively search directories for a regex pattern
rtok     v0.10.0  Reduce the context AI coding agents must carry
runa     ?        Run AI models locally (GGUF via llama.cpp) or through the OpenAI and Anthropic APIs
swarfr   v0.1.0   Shrink Cargo target directories without slowing builds
? means the latest release could not be checked: runa
```

```text
$ ketch list
   package  installed        latest                      source
   cox                       v0.1.0                      github:pyrlyn/cox
*  fd       v10.4.2          v10.5.0 (update available)  github:sharkdp/fd
   ketch                     v0.6.1                      github:pyrlyn/ketch
*  ripgrep  14.1.1           15.2.0 (update available)   github:BurntSushi/ripgrep
*  rtok     v0.9.0 (pinned)  v0.10.0                     github:pyrlyn/rtok
   runa                      ?                           github:pyrlyn/runa
   swarfr                    v0.1.0                      github:listepo/swarfr
2 updates available: ketch upgrade fd ripgrep
? means the latest release could not be checked: runa
```

Без мережі:

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
ketch list local --json      # встановлені пакети, для скриптів
ketch list --names-only      # усі назви пакетів, по одній на рядок
ketch list remote --json     # реєстр з останніми версіями
```

### `ketch outdated [--json] [--pre]`

Показує встановлені пакети, що мають новіший реліз. Недоступне джерело —
це попередження, а не помилка, якщо тільки взагалі нічого не вдалося перевірити.

```bash
ketch outdated
ketch outdated --json   # {"status","outdated","failed","unreachable"}
```

### `ketch info <PKG> [--assets] [--json]`

Показує подробиці про пакет, встановлений чи ні: джерело, опис, останню
та встановлену версії, посилання, зберігання, перевірку.

```bash
ketch info BurntSushi/ripgrep
ketch info rg --assets   # кожен асет релізу з оцінкою та її причиною
```

Аліас — `ketch show`.

### `ketch why <PKG> [--json]`

Пояснює, як було б розв'язано пакет, не встановлюючи його: який рівень маніфестів
відповів, який реліз і асет було обрано й чому.

```bash
ketch why sharkdp/fd@v10.2.0
ketch why rg --json
```

### `ketch changelog <PKG> [--latest] [--file] [--release]`

Показує, що змінилося: файл changelog, що постачається з пакетом, або нотатки до релізу,
опубліковані разом із ним. Для встановленої версії віддає перевагу файлу на диску;
інакше використовує нотатки.

```bash
ketch changelog ripgrep            # встановлена версія
ketch changelog ripgrep --latest   # натомість найновіший реліз
ketch changelog ripgrep --file     # лише файл із постачання
ketch changelog ripgrep --release  # лише опубліковані нотатки
```

### `ketch search <QUERY>... [-n <LIMIT>]`

Шукає на GitHub (і в плагінах із пошуком) репозиторії, які можна встановити.

```bash
ketch search fuzzy finder
ketch search ripgrep -n 5
```

## Відтворення

### `ketch lock [--file <FILE>] [--check]`

Записує `./ketch.lock` за тим, що встановлено, — відтворюваний запис
утиліт машини, закріплених на точних релізах. Див. [LOCKFILE.md](LOCKFILE.md).

```bash
ketch lock              # записати ./ketch.lock
ketch lock --check      # чи не розійшлося дерево з ним?
ketch lock -f tools.lock
```

### `ketch sync [--file <FILE>] [--prune] [--dry-run]`

Встановлює все, що названо в `ketch.lock`, у названих там версіях. Пакет, не
згаданий у lock-файлі, не чіпається, якщо не передано `--prune` (який спершу питає,
бо може знищити чиюсь роботу).

```bash
ketch sync                 # лише відсутні або розбіжні пакети
ketch sync --dry-run       # показати план +/~/−, нічого не змінюючи
ketch sync --prune --yes   # також видалити зайве, не питаючи
```

## Історія

### `ketch history [PKG] [-n <LIMIT>] [--json]`

Показує, що встановлювалося, оновлювалося й видалялося, починаючи з найновішого. Без пакета
показує все дерево на одній часовій шкалі.

```bash
ketch history
ketch history ripgrep -n 10
```

### `ketch stats [--json]`

Підсумовує все, що ketch записав у `stats.db`.

```bash
ketch stats
ketch stats --json
```

## Реєстр і джерела

### `ketch update`

Оновлює реєстр пакетів у `~/.ketch/registry`. (Для встановлених
пакетів див. `upgrade`.)

```bash
ketch update
```

Запускається автоматично на початку `install` і `upgrade`, якщо
`auto_update` не дорівнює `false` у `~/.ketch/config.toml`
(`KETCH_AUTO_UPDATE=false`).

### `ketch registry validate [DIR] [--fixture <DIR>] [--changed <NAME>] [--json]`

Перевіряє дерево реєстру так само, як pre-push хук: кожен `ketch.toml` розібрано й
перевірено, плюс конфлікти назв. `--fixture` як додатковий доказ виконує офлайн-встановлення записів із
локальних файлів.

```bash
ketch registry validate
ketch registry validate ./ketch-registry --fixture ./fixtures
```

### `ketch registry status [--json]`

Показує вік і джерело локальної копії реєстру, нічого не завантажуючи.

```bash
ketch registry status
```

### `ketch registry push [--file <FILE>] [--registry <REPO>] [--dry-run] [--yes]`

Порівнює `ketch.toml` цього проєкту з копією в реєстрі й відкриває з ним пул-реквест.
Для нового пакета відкриває одразу; для оновлення показує диф і питає;
якщо файли ідентичні, повідомляє `unchanged` і нічого не відкриває. Див.
[REGISTRY.md](REGISTRY.md).

```bash
KETCH_GITHUB_TOKEN=... ketch registry push
ketch registry push --dry-run
```

### `ketch plugin list [--json]` / `ketch plugin dir`

Показує виявлені плагіни джерел (`ketch-source-<scheme>` у `~/.ketch/plugins`
і в `PATH`) або друкує каталог плагінів. Див. [PLUGINS.md](PLUGINS.md).

```bash
ketch plugin list
ketch plugin dir
```

## Оточення

### `ketch doctor [--fix] [--json]`

Перевіряє оточення й дерево встановлення: версію, налаштування PATH, перевірки
платформи, лог, вік реєстру, відповідність сховища `state.json`. Повертає ненульовий код, якщо
перевірка не пройшла. У Windows також попереджає про записи в користувацькому PATH, що вказують на каталог bin
ketch, теки якого вже немає, — цього кореня або будь-якої `.ketch\bin`.

```bash
ketch doctor
ketch doctor --fix    # виправити те, що можна виправити (налаштування PATH)
```

### `ketch path [install|uninstall|status]`

Додає каталог bin ketch до PATH. Просто `ketch path` показує таблицю
стану; це типова підкоманда.

```bash
ketch path                  # каталог bin, стан PATH, таблиця за оболонками
ketch path install          # змінити файли запуску оболонок (питає для кожної)
ketch path install --print  # надрукувати рядок, щоб додати його вручну
ketch path install --all    # діяти для всіх відомих оболонок
ketch path uninstall        # знову прибрати блок
```

### `ketch config create [--file <FILE>] [--force] [--yes]`

Записує конфігурацію пакета (`ketch.toml`) за відповідями на запитання — джерело, назва,
записи bin, шаблони асетів, — потім показує й записує файл. Пакет, який
ставить посилання на бінарники, має називати команду, яку додає до PATH, тому перший запис `bin`
команда запитує, а не пропонує; його назва типово — назва пакета.
Відповіді можна передати через stdin, по одній на рядок. Див. [MANIFESTS.md](MANIFESTS.md).

```bash
ketch config create
ketch config create --file ./ketch.toml --yes
```

### `ketch config reset [--yes]`

Записує `config.toml` у корені ketch зі скомпільованими типовими значеннями. Спершу питає,
якщо не передано `--yes`; зберігає резервну копію наявного файлу поруч із ним як
`config.toml.bak-<unix-seconds>`, якщо файл є й не збігається з уже
наявною поруч резервною копією.

```bash
ketch config reset
ketch config reset --yes
```

### `ketch completions <SHELL> [--install]`

Друкує скрипт автодоповнення для оболонки або з `--install` встановлює його в
користувацький каталог автодоповнень оболонки.

```bash
ketch completions zsh > _ketch
ketch completions bash --install
```

Скрипти для bash і PowerShell також доповнюють назви пакетів: встановлених — після
`uninstall`, `upgrade`, `pin`, `unpin`, `link`, `unlink`, `info`, `why`,
`changelog` і `rollback`, і назви з локальної копії реєстру — після
`install` і `search`. Скрипт запитує їх у бінарника внутрішньою командою
`ketch __complete <installed|registry> [PREFIX]`, яка читає файл стану та
вже наявний на диску реєстр і ніколи не звертається до мережі. `--root`, вказаний раніше
в командному рядку, враховується.

`--install` записує скрипт bash у
`${XDG_DATA_HOME:-~/.local/share}/bash-completion/completions/ketch`, звідки
bash-completion 2 завантажує його під час першого використання. У macOS для цього системної
оболонки замало: `/bin/bash` — це 3.2, а bash-completion 2 потрібен bash 4.2 або новіший.
Встановіть свіжий bash і bash-completion 2 (через Homebrew:
`brew install bash bash-completion@2`), зробіть цей bash оболонкою входу й
підключайте `bash_completion` з bash-completion у `~/.bashrc`, як сказано в його
примітках. Сам скрипт працює й під bash
3.2, тож `eval "$(ketch completions bash)"` у `~/.bashrc` працює і без них.

У Windows `--install` (і `ketch self install`, який встановлює скрипти для всіх
оболонок) також вмикає автодоповнення, бо жодна з оболонок не завантажує
каталог автодоповнень сама:

- **PowerShell.** Скрипт потрапляє в `Documents\PowerShell\Completions\ketch.ps1`,
  а блок між `# >>> ketch >>>` і `# <<< ketch <<<` у профілі
  CurrentUserAllHosts для PowerShell 7 (`Documents\PowerShell\profile.ps1`)
  і Windows PowerShell 5.1 (`Documents\WindowsPowerShell\profile.ps1`)
  підключає його через dot-sourcing. Documents — це тека, про яку повідомляє PowerShell, тож перенаправлення
  в OneDrive враховується. Ще не наявний профіль створюється, лише якщо
  цю редакцію встановлено і її політика виконання дозволяє локальні скрипти;
  інакше ketch пояснює, чому не став його чіпати.
- **cmd.** У cmd немає програмованого автодоповнення, тому він отримує макроси doskey:
  `ki` (`ketch install`), `ku` (`ketch upgrade`), `kl` (`ketch list`) і `kun`
  (`ketch uninstall`), кожен з яких передає свої аргументи далі. Вони лежать у
  `<root>\share\ketch\ketch.doskey` і завантажуються командою
  `doskey /macrofile="…"`, доданою до
  `HKCU\Software\Microsoft\Command Processor\AutoRun` через ` & ` після
  того, що AutoRun уже виконує.

`ketch self uninstall` прибирає блоки з профілів (видаляючи профіль, у якому
більше нічого не було) і видаляє з AutoRun рівно свою команду, лишаючи
попереднє значення як було або видаляючи значення, якщо в ньому була лише команда ketch.

## Сам ketch

### `ketch self install [--force] [--link-dir <DIR>]`

Встановлює цей реліз ketch як пакет — у сховище й каталог bin. Саме це
запускають інсталятори curl/PowerShell/Homebrew.

```bash
ketch self install
```

### `ketch self upgrade [--dry-run] [--force] [--yes]`

Оновлює ketch до останнього релізу. Аліас — `ketch self update`. ketch,
встановлений через mise й жодного разу не встановлений через `self install`, оновлює mise:
команда відмовляється й називає `mise upgrade`.

```bash
ketch self upgrade
ketch self upgrade --dry-run
```

### `ketch self version`

Друкує запущену версію та де вона розташована (target, корінь, бінарник, PATH).

```bash
ketch self version
```

### `ketch self uninstall [--keep-packages] [--dry-run] [--yes]`

Видаляє ketch і все, що він встановив, назавжди. Перелічує, що збирається
видалити, і спершу питає. `--keep-packages` видаляє лише ketch. Для ketch,
встановленого через mise, також питає, чи запустити `mise unuse -g` для його власної копії;
`--yes` відповідає й на це.
У Windows запущений `ketch.exe` не може видалити сам себе, тому решта
кореня видаляється фоновим процесом після виходу ketch.

У Windows він також видаляє те, що ketch записав до реєстру Windows. Зараз це
лише каталог bin у користувацькому PATH (`HKCU\Environment\Path`), записаний
`install.ps1` або `ketch path install`. Запис знаходиться хоч би як він був
написаний: регістр, лапки, `/` чи `\`, кінцевий роздільник або коротка назва
8.3. `--keep-packages` лишає його, як і блоки в оболонках, бо
він потрібен пакетам, що лишаються в каталозі bin. ketch ніколи не реєструє себе в «Програмах і
можливостях», тож видаляти там нічого.

Він також видаляє блоки з профілів PowerShell і додаток до AutoRun cmd,
які вмикають автодоповнення, — їх і з `--keep-packages`, бо обидва
завантажують сам ketch.

```bash
ketch self uninstall --dry-run
ketch self uninstall --keep-packages
```
