---
title: ketch
tagline: Ловите релизы прямо с GitHub — пакетный менеджер в одном бинарнике для консольных утилит и приложений на macOS, Linux и Windows.
repo: https://github.com/pyrlyn/ketch
homepage: https://github.com/pyrlyn/ketch
install: 'curl -fsSL https://raw.githubusercontent.com/pyrlyn/ketch/main/install.sh | bash'
install_alternatives:
  - 'irm https://raw.githubusercontent.com/pyrlyn/ketch/main/install.ps1 | iex'
  - 'brew install --cask pyrlyn/tap/ketch'
  - 'mise use -g github:listepo/ketch'
version: "0.6.0"
accent: "#3DDCB0"
accentLight: "#0F6F5C"
order: 2
lang: ru
---

<!-- Website copy for the listepo project site. The sync-docs workflow copies this file to
pyrlyn/landing (main) as content/projects/ketch.md on every change to main and on every v*
tag; front matter follows CONTENT_CONTRACT.md in that repository.
Sources (checked 2026-09-27): README.md and the clap CLI in src/cli.rs; version from the latest
GitHub release (v0.6.0); accent is the dark-theme --accent in site/DESIGN.md. -->


## Обзор

ketch устанавливает консольные утилиты и приложения из релизов GitHub на macOS, Linux и Windows.
Никаких tap, никаких формул, никакой сборки — ketch скачивает то, что проект уже публикует, проверяет
это и кладёт в ваш `PATH`.

Большинство консольных утилит уже опубликованы как ассет релиза, собранный под вашу машину. ketch
выбирает нужный, сверяет контрольную сумму, опубликованную проектом, распаковывает его в версионированное
хранилище и ставит на него ссылку в `PATH`.

## Возможности

- **Любой репозиторий с релизами.** Ни формулы, ни tap, ни ожидания мейнтейнера. Укажите
  `owner/repo`, имя из реестра или точную версию.
- **Проверено, а не просто скачано.** Опубликованные суммы SHA-256 сверяются с тем, что оказалось на
  диске. `require_checksums` отклоняет всё, что их не публикует, а собственные обновления ketch никогда
  не принимают доверие при первом использовании (trust-on-first-use).
- **Приложения, а не только бинарники.** Бандл `.app` отправляется в `/Applications`, карантин снимается,
  если подпись в порядке, и при удалении он убирается начисто.
- **Одно дерево.** Всё хранится в `~/.ketch`: версионированные файлы пакетов, ссылки, состояние. После удаления
  ничего не остаётся.
- **Обновления, которые можно отменить.** Обновление сохраняет предыдущую версию на диске; `ketch rollback`
  переключает ссылки обратно без повторной загрузки.
- **Воспроизводимые машины.** `ketch lock` записывает `ketch.lock` по тому, что установлено, а
  `ketch sync` устанавливает ровно эти версии на другой машине.
- **Источники помимо GitHub.** Плагин — это один исполняемый файл, отвечающий в JSON: устанавливайте из
  GitLab, Gitea или внутреннего сервера артефактов без перекомпиляции.
- **Локальная установка.** `ketch install --path` принимает бинарник, архив, симлинк или `.app`, которые
  уже лежат на диске.

## Установка

macOS и Linux:

```bash
curl -fsSL https://raw.githubusercontent.com/pyrlyn/ketch/main/install.sh | bash
```

Windows (PowerShell):

```powershell
irm https://raw.githubusercontent.com/pyrlyn/ketch/main/install.ps1 | iex
```

Homebrew или mise:

```bash
brew install --cask pyrlyn/tap/ketch
mise use -g github:listepo/ketch && ketch path install
```

Затем убедитесь, что `~/.ketch/bin` есть в `PATH`; `ketch doctor` скажет, если это не так.

## Примеры использования

Установка из любого репозитория, публикующего релизы, по имени из реестра или точной версии:

```bash
ketch install BurntSushi/ripgrep
ketch install rg
ketch install sharkdp/fd@v10.2.0
ketch install --path ./target/release/mytool --name mytool
```

Держите установленные утилиты свежими и откатывайтесь, если обновление ведёт себя плохо:

```bash
ketch list
ketch outdated
ketch upgrade
ketch rollback <pkg>
```

Разберитесь в пакете до установки:

```bash
ketch info <pkg> --assets
ketch why <pkg>
ketch search <query>
ketch changelog <pkg>
```

Повторите набор утилит одной машины на другой:

```bash
ketch lock
ketch sync
```

Обслуживание самого ketch:

```bash
ketch doctor --fix
ketch self upgrade
ketch self uninstall
```

## Ссылки

- Репозиторий: <https://github.com/pyrlyn/ketch>
- Документация: <https://github.com/pyrlyn/ketch/tree/main/docs>
- Справочник команд: <https://github.com/pyrlyn/ketch/blob/main/docs/COMMANDS.md>
- Реестр пакетов: <https://github.com/pyrlyn/ketch-registry>
- Релизы: <https://github.com/pyrlyn/ketch/releases>
- Лицензия: на ваш выбор GNU GPLv3, бесплатная (royalty-free) лицензия для проприетарных настольных, мобильных и
  веб-приложений (с указанием авторства) или коммерческая лицензия (см. <https://github.com/pyrlyn/ketch#license>)
