---
lang: ru
---

# Сборник рецептов

Короткие рецепты, которые можно запускать. Каждый выполнен на ketch 0.10.0, собранном из `main`,
в одноразовом корне, так что ваша настоящая установка не затрагивается: `--root ./sandbox`
направляет ketch в другое дерево, а `--yes` отвечает на его запросы подтверждения. Все команды и
флаги описаны в [COMMANDS.md](COMMANDS.md).

## Попробовать ketch, не трогая `~/.ketch`

```bash
ketch --root ./sandbox install sharkdp/fd@v10.2.0
./sandbox/bin/fd --version        # fd 10.2.0
rm -rf ./sandbox                  # всё, что поставил ketch, лежало внутри
```

ketch предупредит, что `./sandbox/bin` нет в `PATH`; для настоящего корня это исправляет
`ketch path install`.

## Обновить и вернуться

```bash
ketch --root ./sandbox upgrade fd --yes     # fd v10.2.0 -> v10.5.0
ketch --root ./sandbox rollback fd --to v10.2.0
./sandbox/bin/fd --version                  # fd 10.2.0
```

Без `--to` `rollback` возвращает предыдущую сохранённую версию. Обновление оставляет прежний
префикс на диске до `ketch prune`; `ketch prune --keep 2` хранит по две предыдущие версии пакета.

## Зафиксировать версию

```bash
ketch --root ./sandbox pin fd               # pinned fd v10.5.0
ketch --root ./sandbox upgrade --yes        # fd не трогает
ketch --root ./sandbox unpin fd
```

## Посмотреть до установки

```bash
ketch releases sharkdp/fd                   # новые сверху; установленный помечен *
ketch why BurntSushi/ripgrep                # как пакет разрешится: манифест, версия, платформа
```

## Установить файл с вашей машины

```bash
printf '#!/bin/sh\necho hello\n' > hello && chmod +x hello
ketch --root ./sandbox install --path ./hello
ketch --root ./sandbox info hello           # источник, префикс, канал, «verified first use»
```

У локального файла нет опубликованной контрольной суммы, поэтому ketch предупреждает и доверяет
ему при первом использовании.

## Воспроизвести машину по lock-файлу

```bash
ketch lock                                  # пишет ./ketch.lock (закоммитьте его)
ketch lock --check                          # код 0, если дерево совпадает с lock-файлом
ketch --root ./other sync --dry-run         # + fd v10.5.0: что поставил бы sync
ketch --root ./other sync                   # ставит каждый пакет на зафиксированном теге
```

Lock-файл хранит тег, имя ассета и SHA-256 для машины, которая его записала; на другой платформе
`sync` выбирает подходящий ассет и проверяет его по источнику ([LOCKFILE.md](LOCKFILE.md)).
