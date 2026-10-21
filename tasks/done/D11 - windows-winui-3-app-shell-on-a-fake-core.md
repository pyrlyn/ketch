---
id: D11
title: "Windows: WinUI 3 app shell on a fake core"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`desktop/windows` is a WinUI 3 app on the Windows App SDK and .NET 10, built with `dotnet build` and no Visual Studio. `Ketch.AppCore` holds the fake core and the store and tests on any OS. The app has Installed, Discover, Updates, Activity, Doctor, Settings and a package page, with `ContentDialog` and `InfoBar`, Mica, and light, dark and high contrast from the token file. The `ketch-win-app` job runs `dotnet test` and the Release build. Landed in #247.
