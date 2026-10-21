---
id: F14
title: "Design system for the macOS app: `DESIGN.md` and tokens"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`desktop/macos/DESIGN.md` follows the DESIGN.md spec, with generated front matter. `desktop/design/tokens.json` is the one token source, and `desktop/design/build.mjs` writes the Swift, XAML and CSS copies plus the preview stylesheet. `just design-check` fails when a generated file drifts, when text misses WCAG AA on its glass background, or when the DESIGN.md lint fails. Landed in #188. An Accessibility Inspector pass on the real Liquid Glass material still needs a Mac; the contrast numbers the script checks are the ones in the tokens.
`desktop/windows` is a WinUI 3 app on the Windows App SDK and .NET 10, built with `dotnet build` and no Visual Studio. `Ketch.AppCore` holds the fake core and the store and tests on any OS. The app has Installed, Discover, Updates, Activity, Doctor, Settings and a package page, with `ContentDialog` and `InfoBar`, Mica, and light, dark and high contrast from the token file. The `ketch-win-app` job runs `dotnet test` and the Release build. Landed in #247.
