# Design tokens

`tokens.json` is copied unchanged from the Rust tree's `desktop/design/tokens.json`, the one
source of ketch's colours, sizes and type ([W3C DTCG 2025.10](https://www.designtokens.org/tr/2025.10/format/)).
It is never edited here: when Rust changes it, copy it again.

The app reads the brand subset the Rust Windows and Linux apps take (`BRAND` in the Rust tree's
`desktop/design/build.mjs`): `color.accent`, `color.status`, `space`, `radius` and
`typography`, in light, dark, high contrast and high contrast dark. A missing appearance falls
back as the generator's does: high contrast dark to dark to light, high contrast to light.
Under Material 3, glass, wash, elevation and motion stay out and surfaces are Material 3's own.
The Linux look reads none of this: its colours are the tokens in the `kotlin-adwaita` package.
On macOS the app also reads the macOS tokens (`Tokens.macColor`), and `ui/glass/Palette.kt` maps them
onto the colours of the `kotlin-glass` package.

The build puts the file on the classpath; `ketch.desktop.ui.Tokens` reads it at start, and
`TokensTest` fails when a brand token is missing an appearance or its hex disagrees with its
components.
