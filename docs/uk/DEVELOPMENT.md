---
lang: uk
---

# Розробка

Збирання, тестування та випуск ketch. Чекліст для контриб'юторів — у [CONTRIBUTING.md](../../CONTRIBUTING.md).

## Збирання з вихідного коду

```bash
cargo build --release                    # target/release/ketch
cargo test --workspace                   # модульні тести та end-to-end набір; без мережі
cargo clippy --workspace --all-targets   # має бути чисто
cargo fmt --all --check                  # має бути чисто
```

Вихідний код — це Cargo workspace: бінарник `ketch` у корені містить командний рядок, а
[`crates/ketch-core`](../../crates/ketch-core) — усе, що він робить: розв'язання, завантаження,
перевірку, розпакування та лінкування.

Запускайте бінарник на одноразовому дереві замість справжнього `~/.ketch`:

```bash
KETCH_ROOT=/tmp/ketch-scratch cargo run -- doctor
```

## Випуск

Нічого не вводиться вручну. Actions → **Bump and release** (`bump.yml`) — єдиний спосіб
випустити реліз: `scripts/release.sh` піднімає версію і пише запис `CHANGELOG.md` одним
комітом, bump відкриває з ним pull request, чекає всіх обов'язкових перевірок, вливає його
через rebase і лише потім ставить тег на коміт, що потрапив у `main`, створює реліз і запускає
`release.yml`, згенерований [cargo-dist](https://github.com/axodotdev/cargo-dist): він збирає
та підписує обидві архітектури macOS (Linux і Windows без підпису) і лише після збирання всіх
цілей завантажує архіви та публікує реліз, після чого задача `tap` оновлює cask у
`pyrlyn/homebrew-tap`. Червоні перевірки не залишають ні тегу, ні релізу.

CI (`ci.yml`) запускається на пушах у `main`, на pull request не в статусі чернетки та вручну
через `workflow_dispatch`. Перед злиттям гілки запустіть перевірку на цьому ref і зливайте
лише за зеленого результату:

```bash
gh workflow run ci.yml --ref <branch>
```

```bash
just release minor --dry-run    # версія, яку отримає реліз; нічого не змінює
just release minor              # запускає Bump and release (gh workflow run bump.yml)
```

ketch немає на crates.io — він постачається архівом у GitHub-релізі, тому крейт ніхто не
публікує.
