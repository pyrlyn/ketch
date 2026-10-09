# ketch brandbook

Only what is specific to ketch. Everything else is the [Pyrlyn base layer](https://github.com/pyrlyn/brand/blob/v0.3.0/base/DESIGN.md) from `@pyrlyn/brand` (pinned in `package.json`).
Tokens: [`tokens.json`](tokens.json) (extends `@pyrlyn/brand/base/tokens.json`), built to
`dist/tokens.css` by `node build.mjs`. Source: `pyrlyn/ketch` at `7bec33c`, `site/static/css/main.css` and
`site/DESIGN.md`.

## Idea

Catch releases from GitHub: a hook catching a release-tag chip. Nerd + flat + material + glass.

## Colour

- Light is the default theme (the ketch site's `:root`); dark is first-class.
- Accent: deep teal `#0F6F5C` on light, mint `#3DDCB0` on dark (hover `accent-bright`).
- Mark tile is always dark teal `#0A3D36` so the mint hook stays legible on both canvases.
- Coral `#FF6B5B` is only the release-tag chip (`--ketch-tag-coral`).
- Terminal chrome (`--ketch-term-*`) stays dark in both themes.
- No purple/violet "AI" gradients; don't put light ink on the mark tile.
- Role mapping from the site's names: `canvas`→`bg`, `surface-1`→`surface`, `hairline`→`border`,
  `ink`→`fg`, `ink-muted`→`fg-muted`, `ink-faint`→`fg-subtle`, `accent-soft`→`accent-muted`,
  `accent-on`→`on-accent`, `elev-1..3`→`shadow-e1..e3`. Kept as `--ketch-*`: `accent-bright`,
  `glass-fill`, `glass-border`, `focus-ring`, `tag-coral`, `stage`, `term-*`.
- Status colours follow the desktop app (`desktop/design/tokens.json` `status.*`): on light, fills
  success `#1F9D57`, warn `#E0A100`, danger `#E0314B` and text `#12683A`, `#774B00`, `#A81E34`; on dark
  `#4FD98F`, `#F2C14E`, danger fill `#FF7584` and text `#FF8E9A`. Use the `*-fg` variant for text. The
  light warn fill is 2.12:1 on `bg`: give it an outline or an `fg` label (7.89:1), never use it as text.

## Type

The ketch site sets the mono stack with JetBrains Mono first (`"JetBrains Mono", "IBM Plex Mono", …`)
and a system sans for body. The base ships IBM Plex Mono; the ketch site keeps its own font stack.

## Logo

| File (`logo/`) | Use |
|---|---|
| `ketch-mark.svg` | Mark 64×64: dark teal tile, mint hook, coral release chip |
| `ketch-wordmark.svg` | Mark + `ketch` in mono 600 (live text: needs JetBrains Mono or IBM Plex Mono) |
| `ketch-favicon.svg` | Simplified 32×32 mark |
| `ketch-favicon-legacy.svg` | Previous anchor-style favicon |
| `png/ketch-favicon-{32x32,64x64}.png`, `png/ketch-apple-touch-icon-180.png`, `png/ketch-icon-logo-{512,1024}.png` | Rendered from the SVGs |

No wordmark PNG: the wordmark is live text, so a raster depends on the installed font.
