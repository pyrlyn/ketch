---
title: ketch
tagline: Ловіть релізи просто з GitHub — менеджер пакетів в одному бінарнику для консольних утиліт і застосунків на macOS, Linux і Windows.
repo: https://github.com/pyrlyn/ketch
install: 'curl -fsSL https://raw.githubusercontent.com/pyrlyn/ketch/main/install.sh | bash'
install_alternatives:
  - 'irm https://raw.githubusercontent.com/pyrlyn/ketch/main/install.ps1 | iex'
  - 'brew install --cask pyrlyn/tap/ketch'
  - 'mise use -g github:pyrlyn/ketch'
version: "0.6.0"
accent: "#3DDCB0"
accentLight: "#0F6F5C"
order: 2
lang: uk
---

<!-- Website copy for the pyrlyn project site. The sync-docs workflow copies this file to
pyrlyn/landing (main) as content/projects/ketch.md on every change to main and on every v*
tag; front matter follows CONTENT_CONTRACT.md in that repository.
Sources (checked 2026-09-27): README.md and the clap CLI in src/cli.rs; version from the latest
GitHub release (v0.6.0); accent is the dark-theme --accent in site/DESIGN.md. -->


## Огляд

ketch встановлює консольні утиліти й застосунки з релізів GitHub на macOS, Linux і Windows.
Жодних tap, жодних формул, жодного збирання — ketch завантажує те, що проєкт уже публікує, перевіряє
це й кладе у ваш `PATH`.

Більшість консольних утиліт уже опубліковані як асет релізу, зібраний під вашу машину. ketch
обирає потрібний, звіряє контрольну суму, опубліковану проєктом, розпаковує його у версіоноване
сховище й додає на нього посилання в `PATH`.

## Можливості

- **Будь-який репозиторій із релізами.** Ні формули, ні tap, ні очікування на мейнтейнера. Вкажіть
  `owner/repo`, назву з реєстру або точну версію.
- **Перевірено, а не просто завантажено.** Опубліковані суми SHA-256 звіряються з тим, що опинилося на
  диску. `require_checksums` відхиляє все, що їх не публікує, а власні оновлення ketch ніколи
  не приймають довіру під час першого використання (trust-on-first-use).
- **Застосунки, а не лише бінарники.** Бандл `.app` потрапляє до `/Applications`, карантин знімається,
  якщо підпис гаразд, і під час видалення він прибирається начисто.
- **Одне дерево.** Усе зберігається в `~/.ketch`: версіоновані файли пакетів, посилання, стан. Після видалення
  нічого не лишається.
- **Оновлення, які можна скасувати.** Оновлення зберігає попередню версію на диску; `ketch rollback`
  перемикає посилання назад без повторного завантаження.
- **Відтворювані машини.** `ketch lock` записує `ketch.lock` за тим, що встановлено, а
  `ketch sync` встановлює саме ці версії на іншій машині.
- **Джерела, крім GitHub.** Плагін — це один виконуваний файл, що відповідає в JSON: встановлюйте з
  GitLab, Gitea або внутрішнього сервера артефактів без перекомпіляції.
- **Локальне встановлення.** `ketch install --path` приймає бінарник, архів, символьне посилання або `.app`, які
  вже лежать на диску.

## Встановлення

macOS і Linux:

```bash
curl -fsSL https://raw.githubusercontent.com/pyrlyn/ketch/main/install.sh | bash
```

Windows (PowerShell):

```powershell
irm https://raw.githubusercontent.com/pyrlyn/ketch/main/install.ps1 | iex
```

Homebrew або mise:

```bash
brew install --cask pyrlyn/tap/ketch
mise use -g github:pyrlyn/ketch && ketch path install
```

Потім переконайтеся, що `~/.ketch/bin` є в `PATH`; `ketch doctor` скаже, якщо це не так.

## Приклади використання

Встановлення з будь-якого репозиторію, що публікує релізи, за назвою з реєстру або точною версією:

```bash
ketch install BurntSushi/ripgrep
ketch install rg
ketch install sharkdp/fd@v10.2.0
ketch install --path ./target/release/mytool --name mytool
```

Тримайте встановлені утиліти актуальними й відкочуйтеся, якщо оновлення поводиться погано:

```bash
ketch list
ketch outdated
ketch upgrade
ketch rollback <pkg>
```

Розберіться в пакеті до встановлення:

```bash
ketch info <pkg> --assets
ketch why <pkg>
ketch search <query>
ketch changelog <pkg>
```

Відтворіть набір утиліт однієї машини на іншій:

```bash
ketch lock
ketch sync
```

Обслуговування самого ketch:

```bash
ketch doctor --fix
ketch self upgrade
ketch self uninstall
```

## Посилання

- Репозиторій: <https://github.com/pyrlyn/ketch>
- Реєстр пакетів: <https://github.com/pyrlyn/ketch-registry>
- Релізи: <https://github.com/pyrlyn/ketch/releases>
- Ліцензія: на ваш вибір GNU GPLv3, безкоштовна (royalty-free) ліцензія для пропрієтарних настільних, мобільних і
  вебзастосунків (із зазначенням авторства) або комерційна ліцензія (див. <https://github.com/pyrlyn/ketch#license>)
