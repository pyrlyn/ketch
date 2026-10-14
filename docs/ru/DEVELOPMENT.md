---
lang: ru
---

# Разработка

Сборка, тестирование и выпуск ketch. Чек-лист для контрибьюторов — в [CONTRIBUTING.md](../../CONTRIBUTING.md).

## Сборка из исходников

```bash
cargo build --release                    # target/release/ketch
cargo test --workspace                   # модульные тесты и end-to-end набор; без сети
cargo clippy --workspace --all-targets   # должно быть чисто
cargo fmt --all --check                  # должно быть чисто
```

Исходники — это Cargo workspace: бинарник `ketch` в корне содержит командную строку, а
[`crates/ketch-core`](../../crates/ketch-core) — всё, что он делает: разрешение, загрузку,
проверку, распаковку и линковку.

Запускайте бинарник на одноразовом дереве вместо настоящего `~/.ketch`:

```bash
KETCH_ROOT=/tmp/ketch-scratch cargo run -- doctor
```

## Выпуск

Ничего не вводится вручную. Actions → **Bump and release** (`bump.yml`) — единственный способ
выпустить релиз: `scripts/release.sh` поднимает версию и пишет запись `CHANGELOG.md` одним
коммитом, bump открывает с ним pull request, ждёт всех обязательных проверок, вливает его
через rebase и только затем ставит тег на коммит, попавший в `main`, создаёт релиз и запускает
`release.yml`, сгенерированный [cargo-dist](https://github.com/axodotdev/cargo-dist): он собирает
и подписывает обе архитектуры macOS (Linux и Windows без подписи) и только после сборки всех
целей загружает архивы и публикует релиз, после чего задача `tap` обновляет cask в
`pyrlyn/homebrew-tap`. Красные проверки не оставляют ни тега, ни релиза.

CI (`ci.yml`) запускается на пушах в `main`, на pull request не в статусе черновика и вручную
через `workflow_dispatch`. Перед слиянием ветки запустите проверку на этом ref и сливайте
только при зелёном результате:

```bash
gh workflow run ci.yml --ref <branch>
```

```bash
just release minor --dry-run    # версия, которую получит релиз; ничего не меняет
just release minor              # запускает Bump and release (gh workflow run bump.yml)
```

ketch нет на crates.io — он поставляется архивом в GitHub-релизе, поэтому крейт никто не
публикует.
