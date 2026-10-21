---
id: B94
title: "`install.ps1` Join-Path takes three children"
status: done
created_at: 1792583854
updated_at: 1792583854
---

Windows PowerShell 5.1's `Join-Path` accepts one child path. `Join-Path $TempDir 'ketch' $BinaryName` is `-AdditionalChildPath` in pwsh 6+ and throws in 5.1, which is the shell `irm | iex` runs in, after the archive has already been extracted. The nested member is joined twice. The `powershell` CI job runs the script under `shell: powershell` past extraction, with the binary only inside `ketch/`.

Test: `the_nested_archive_path_is_joined_for_windows_powershell_51`.
