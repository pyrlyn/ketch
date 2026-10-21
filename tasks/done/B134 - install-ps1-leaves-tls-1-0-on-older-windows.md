---
id: B134
title: "`install.ps1` leaves TLS 1.0 on older Windows"
status: done
created_at: 1792583854
updated_at: 1792583854
---

Windows PowerShell 5.1 on older Windows 10 and Server defaults `[Net.ServicePointManager]::SecurityProtocol` to TLS 1.0/1.1. GitHub refuses those, so `irm | iex` failed before the archive was fetched. The installer now adds `Tls12` with `-bor`, which turns TLS 1.2 on and leaves TLS 1.3 in place where the host already enabled it.

Test: `the_script_enables_tls12_without_dropping_newer_protocols`.
