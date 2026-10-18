---
lang: uk
---

# Збірник рецептів

Короткі рецепти, які можна запускати. Кожен виконано на ketch 0.10.0, зібраному з `main`, в
одноразовому корені, тож ваше справжнє встановлення не зачіпається: `--root ./sandbox`
спрямовує ketch в інше дерево, а `--yes` відповідає на його запити підтвердження. Усі команди й
прапорці описано в [COMMANDS.md](COMMANDS.md).

## Спробувати ketch, не чіпаючи `~/.ketch`

```bash
ketch --root ./sandbox install sharkdp/fd@v10.2.0
./sandbox/bin/fd --version        # fd 10.2.0
rm -rf ./sandbox                  # усе, що поставив ketch, лежало всередині
```

ketch попередить, що `./sandbox/bin` немає в `PATH`; для справжнього кореня це виправляє
`ketch path install`.

## Оновити й повернутися

```bash
ketch --root ./sandbox upgrade fd --yes     # fd v10.2.0 -> v10.5.0
ketch --root ./sandbox rollback fd --to v10.2.0
./sandbox/bin/fd --version                  # fd 10.2.0
```

Без `--to` `rollback` повертає попередню збережену версію. Оновлення залишає попередній префікс
на диску до `ketch prune`; `ketch prune --keep 2` зберігає по дві попередні версії пакета.

## Зафіксувати версію

```bash
ketch --root ./sandbox pin fd               # pinned fd v10.5.0
ketch --root ./sandbox upgrade --yes        # fd не чіпає
ketch --root ./sandbox unpin fd
```

## Подивитися до встановлення

```bash
ketch releases sharkdp/fd                   # нові зверху; встановлений позначено *
ketch why BurntSushi/ripgrep                # як пакет розв'яжеться: маніфест, версія, платформа
```

## Встановити файл з вашої машини

```bash
printf '#!/bin/sh\necho hello\n' > hello && chmod +x hello
ketch --root ./sandbox install --path ./hello
ketch --root ./sandbox info hello           # джерело, префікс, канал, «verified first use»
```

Локальний файл не має опублікованої контрольної суми, тому ketch попереджає й довіряє йому під
час першого використання.

## Відтворити машину за lock-файлом

```bash
ketch lock                                  # пише ./ketch.lock (закомітьте його)
ketch lock --check                          # код 0, якщо дерево збігається з lock-файлом
ketch --root ./other sync --dry-run         # + fd v10.5.0: що встановив би sync
ketch --root ./other sync                   # ставить кожен пакет на зафіксованому тезі
```

Lock-файл зберігає тег, назву ассета й SHA-256 для машини, яка його записала; на іншій платформі
`sync` обирає відповідний ассет і перевіряє його за джерелом ([LOCKFILE.md](LOCKFILE.md)).
