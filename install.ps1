#Requires -Version 5.1
# Windows bootstrap installer; Unix/macOS use install.sh.

# Under `irm ... | iex` this text runs in the caller's own session. The
# scriptblock keeps its variables, functions and $ErrorActionPreference out of
# that session, and no error path may call `exit`: there it closes the user's
# PowerShell window before the message can be read.
& {
    $ErrorActionPreference = 'Stop'

    # Windows PowerShell 5.1 on older Windows 10 and Server still defaults to
    # TLS 1.0/1.1, which GitHub rejects. OR keeps TLS 1.3 where the host
    # already enabled it.
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

    $SelfRepo = 'pyrlyn/ketch'
    $BinaryName = 'ketch.exe'
    $DefaultRoot = Join-Path $env:USERPROFILE '.ketch'

    $TempDir = $null

    function Write-Help {
        @"
Usage: install.ps1 [OPTIONS]

Install ketch, a Rust CLI for managing GitHub-released apps.

OPTIONS:
  --version <TAG>      Install specific version (default: latest)
  --pre                Install the newest release, prereleases included
  --root <DIR>         Ketch store root (default: $DefaultRoot)
  --install-dir <DIR>  Bootstrap location: where ketch places a link or copy
                       on your PATH (default: <root>\bin)
  --no-modify-path     Don't put the bin dir on PATH
  --help               Show this help message
"@
    }

    function Remove-TempDir {
        if ($null -ne $TempDir -and (Test-Path -LiteralPath $TempDir)) {
            Remove-Item -LiteralPath $TempDir -Recurse -Force
        }
    }

    function Resolve-CallerPath {
        param([string]$Path)
        if ([System.IO.Path]::IsPathRooted($Path)) {
            return $Path
        }
        return (Join-Path (Get-Location).Path $Path)
    }

    function Get-CanonicalPath {
        param([string]$Path)
        if (-not (Test-Path -LiteralPath $Path)) {
            New-Item -ItemType Directory -Path $Path -Force | Out-Null
        }
        return (Resolve-Path -LiteralPath $Path).Path
    }

    function Normalize-PathKey {
        param([string]$Path)
        # Registry PATH values are sometimes quoted (`"C:\Program Files\..."`).
        # Strip quotes before folding so a quoted bin dir matches the unquoted one
        # we are about to install and we do not prepend a duplicate.
        $s = ($Path -replace '/', '\').Trim().Trim('"').Trim("'")
        return $s.TrimEnd('\').ToLowerInvariant()
    }

    # Split a Windows PATH on `;`, keeping `;` inside quotes as part of one entry.
    # A naive `-split ';'` shatters `"C:\weird;name"` and misses the bin dir.
    function Get-UserPathEntries {
        param([string]$PathString)
        $entries = New-Object System.Collections.Generic.List[string]
        if ([string]::IsNullOrEmpty($PathString)) {
            return $entries
        }
        $start = 0
        $inQuotes = $false
        for ($i = 0; $i -lt $PathString.Length; $i++) {
            $c = $PathString[$i]
            if ($c -eq [char]'"') {
                $inQuotes = -not $inQuotes
            }
            elseif ($c -eq [char]';' -and -not $inQuotes) {
                if ($i -gt $start) {
                    [void]$entries.Add($PathString.Substring($start, $i - $start))
                }
                $start = $i + 1
            }
        }
        if ($start -lt $PathString.Length) {
            [void]$entries.Add($PathString.Substring($start))
        }
        return $entries
    }

    function Test-UserPathHas {
        param(
            [string]$PathString,
            [string]$Dir
        )
        $dirKey = Normalize-PathKey $Dir
        foreach ($entry in (Get-UserPathEntries $PathString)) {
            if (-not $entry) {
                continue
            }
            # The value is read unexpanded, so `%USERPROFILE%\.ketch\bin` is
            # compared as Windows would use it.
            $expanded = [Environment]::ExpandEnvironmentVariables($entry)
            if ((Normalize-PathKey $expanded) -eq $dirKey) {
                return $true
            }
        }
        return $false
    }

    # The user Path is read and written through the registry, unexpanded and with
    # its value kind: `[Environment]::GetEnvironmentVariable` expands `%VAR%`, and
    # writing that back as `REG_SZ` freezes every such entry for good.
    function Add-UserPathEntry {
        param([string]$Dir)
        $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
        try {
            $current = ''
            $kind = [Microsoft.Win32.RegistryValueKind]::ExpandString
            if ($key.GetValueNames() -contains 'Path') {
                $current = [string]$key.GetValue('Path', '', 'DoNotExpandEnvironmentNames')
                $existing = $key.GetValueKind('Path')
                if ($existing -eq [Microsoft.Win32.RegistryValueKind]::String) {
                    $kind = $existing
                }
            }
            if (Test-UserPathHas $current $Dir) {
                return $false
            }
            $trimmed = $Dir.TrimEnd('\')
            if ($current -eq '') {
                $next = $trimmed
            }
            else {
                $next = "$trimmed;$current"
            }
            $key.SetValue('Path', $next, $kind)
        }
        finally {
            $key.Close()
        }
        # A registry write notifies nobody; this broadcasts WM_SETTINGCHANGE so
        # a new terminal sees the PATH without a logoff.
        [Environment]::SetEnvironmentVariable('KETCH_ENV_REFRESH', '1', 'User')
        [Environment]::SetEnvironmentVariable('KETCH_ENV_REFRESH', $null, 'User')
        return $true
    }

    function Write-Status {
        param([string]$Message)
        Write-Host $Message
    }

    function Write-ErrorStatus {
        param([string]$Message)
        Write-Host $Message -ForegroundColor Red
    }

    # Unwinds to the bottom of this scriptblock, which decides how the run ends.
    function Stop-Install {
        param([int]$Code = 1)
        $stop = [System.Exception]::new("install.ps1 stopped with status $Code")
        $stop.Data['KetchInstallExit'] = $Code
        throw $stop
    }

    # The first CLI tag in a `/releases` listing. GitHub lists newest first, the
    # same order `/releases/latest` picks from, so `--pre` only widens that choice
    # to prereleases. The repository also releases the macOS app under
    # `desktop-v*` (and its `desktop-appcast` feed); only `v<digit>` tags are this
    # installer's.
    function Select-NewestCliTag {
        param([object[]]$Releases)
        $Releases |
            Where-Object { -not $_.draft -and "$($_.tag_name)" -match '^v[0-9]' } |
            Select-Object -First 1 -ExpandProperty tag_name
    }

    function Install-Ketch {
        # Parse arguments. $InstallDir stays empty unless given so we can tell an
        # explicit --install-dir from the default <root>\bin after $Root is resolved.
        $Version = ''
        $Root = ''
        $InstallDir = ''
        $InstallDirExplicit = $false
        $NoModifyPath = $false
        $Pre = $false

        $argsList = @($args)
        $i = 0
        while ($i -lt $argsList.Count) {
            switch ($argsList[$i]) {
                '--version' {
                    $i++
                    if ($i -ge $argsList.Count) {
                        Write-ErrorStatus 'Error: --version requires a value.'
                        Stop-Install
                    }
                    $Version = $argsList[$i]
                }
                '--root' {
                    $i++
                    if ($i -ge $argsList.Count) {
                        Write-ErrorStatus 'Error: --root requires a value.'
                        Stop-Install
                    }
                    $Root = $argsList[$i]
                }
                '--install-dir' {
                    $i++
                    if ($i -ge $argsList.Count) {
                        Write-ErrorStatus 'Error: --install-dir requires a value.'
                        Stop-Install
                    }
                    $InstallDir = $argsList[$i]
                    $InstallDirExplicit = $true
                }
                '--no-modify-path' {
                    $NoModifyPath = $true
                }
                '--pre' {
                    $Pre = $true
                }
                { $_ -in '--help', '-h' } {
                    Write-Help
                    return
                }
                default {
                    Write-ErrorStatus "Unknown option: $($argsList[$i])"
                    Write-Help
                    Stop-Install
                }
            }
            $i++
        }

        if ($Root -eq '') {
            $Root = $DefaultRoot
        }
        if ($InstallDir -eq '') {
            $InstallDir = Join-Path $Root.TrimEnd('\') 'bin'
        }

        # Both may be relative, and the script cds into a temp directory below: a
        # relative path would be created inside it and deleted with it on exit, leaving
        # nothing installed. Resolve them against the directory the user ran this in.
        $Root = Resolve-CallerPath $Root
        $InstallDir = Resolve-CallerPath $InstallDir

        # KETCH_ROOT is only --root (or its default), never derived from --install-dir.
        $env:KETCH_ROOT = $Root

        $arch = $env:PROCESSOR_ARCHITECTURE
        if ($arch -eq 'x86' -and $env:PROCESSOR_ARCHITEW6432) {
            $arch = $env:PROCESSOR_ARCHITEW6432
        }

        switch ($arch) {
            'AMD64' { $TarballArch = 'x86_64' }
            'ARM64' {
                Write-ErrorStatus 'Error: Unsupported architecture: ARM64'
                Write-Host 'ketch publishes x86_64-pc-windows-msvc releases only; aarch64-pc-windows-msvc is not shipped yet.' -ForegroundColor Red
                Stop-Install
            }
            default {
                Write-ErrorStatus "Error: Unsupported architecture: $arch"
                Stop-Install
            }
        }

        $TarballName = "ketch-$TarballArch-pc-windows-msvc.tar.gz"

        if ($Version -eq '') {
            if ($Pre) {
                Write-Status 'Fetching newest release, prereleases included...'
            }
            else {
                Write-Status 'Fetching latest release...'
            }
            if ($env:KETCH_INSTALL_RELEASE_DIR) {
                $Version = 'v9.9.9'
            }
            elseif ($Pre) {
                try {
                    # Assigned before it is piped: Windows PowerShell 5.1 hands a JSON
                    # array down a pipeline as one object instead of its elements.
                    $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$SelfRepo/releases?per_page=100"
                }
                catch {
                    Write-ErrorStatus 'Error: Failed to fetch releases.'
                    Stop-Install
                }
                $Version = Select-NewestCliTag @($releases)
                if ([string]::IsNullOrWhiteSpace($Version)) {
                    Write-ErrorStatus 'Error: Could not determine latest version.'
                    Stop-Install
                }
            }
            else {
                try {
                    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$SelfRepo/releases/latest"
                }
                catch {
                    Write-ErrorStatus 'Error: Failed to fetch latest release.'
                    Stop-Install
                }
                $Version = $release.tag_name
                if ([string]::IsNullOrWhiteSpace($Version)) {
                    Write-ErrorStatus 'Error: Could not determine latest version.'
                    Stop-Install
                }
            }
        }

        # Release tags are always v-prefixed; accept either form on --version.
        if ($Version -notlike 'v*') {
            $Version = "v$Version"
        }

        Write-Status "Installing ketch version $Version..."

        $TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ketch-install-" + [guid]::NewGuid().ToString())
        New-Item -ItemType Directory -Path $TempDir -Force | Out-Null

        try {
            Push-Location $TempDir

            $TarballPath = Join-Path $TempDir 'ketch.tar.gz'
            $ChecksumsPath = Join-Path $TempDir 'SHA256SUMS'

            if ($env:KETCH_INSTALL_RELEASE_DIR) {
                Copy-Item -LiteralPath (Join-Path $env:KETCH_INSTALL_RELEASE_DIR 'payload.tar.gz') -Destination $TarballPath
                Copy-Item -LiteralPath (Join-Path $env:KETCH_INSTALL_RELEASE_DIR 'SHA256SUMS') -Destination $ChecksumsPath
            }
            else {
                $TarballUrl = "https://github.com/$SelfRepo/releases/download/$Version/$TarballName"
                $ChecksumsUrl = "https://github.com/$SelfRepo/releases/download/$Version/SHA256SUMS"
                Write-Status 'Downloading release assets...'
                try {
                    Invoke-WebRequest -Uri $TarballUrl -OutFile $TarballPath
                    Invoke-WebRequest -Uri $ChecksumsUrl -OutFile $ChecksumsPath
                }
                catch {
                    Write-ErrorStatus "Error: Failed to download $TarballUrl"
                    Stop-Install
                }
            }

            Write-Status 'Verifying checksum...'
            $expectedLine = Select-String -Path $ChecksumsPath -Pattern $TarballName | Select-Object -First 1
            $expectedHash = $null
            if ($null -ne $expectedLine) {
                $expectedHash = ($expectedLine.Line -split '\s+', 2)[0]
            }
            $actualHash = (Get-FileHash -LiteralPath $TarballPath -Algorithm SHA256).Hash.ToLowerInvariant()

            if ([string]::IsNullOrWhiteSpace($expectedHash)) {
                Write-ErrorStatus "Error: SHA256SUMS does not list $TarballName."
                Write-Host 'Refusing to install an unverified binary.' -ForegroundColor Red
                Stop-Install
            }
            if ($expectedHash -ne $actualHash) {
                Write-ErrorStatus 'Error: Checksum verification failed!'
                Write-Host "Expected: $expectedHash"
                Write-Host "Actual:   $actualHash"
                Stop-Install
            }

            Write-Status 'Extracting...'
            # Prefer System32 tar.exe: Git Bash's GNU tar treats 'C:' in a Windows
            # path as a remote host ("Cannot connect to C:"). Fall back to --force-local.
            $systemTar = Join-Path $env:SystemRoot 'System32\tar.exe'
            if (Test-Path -LiteralPath $systemTar) {
                & $systemTar -xzf $TarballPath
            }
            else {
                & tar --force-local -xzf $TarballPath
            }
            if ($LASTEXITCODE -ne 0) {
                Write-ErrorStatus 'Error: Failed to extract the release archive.'
                Stop-Install
            }

            $BinaryPath = $null
            # Windows PowerShell 5.1's Join-Path takes one child. A third
            # positional argument is -AdditionalChildPath, which only exists in
            # pwsh 6+, so the nested archive path is joined twice.
            $candidates = @(
                (Join-Path $TempDir $BinaryName),
                (Join-Path (Join-Path $TempDir 'ketch') $BinaryName)
            )
            foreach ($candidate in $candidates) {
                if (Test-Path -LiteralPath $candidate) {
                    $BinaryPath = $candidate
                    break
                }
            }
            if ($null -eq $BinaryPath) {
                $found = Get-ChildItem -Path $TempDir -Filter $BinaryName -File -Recurse | Select-Object -First 1
                if ($null -ne $found) {
                    $BinaryPath = $found.FullName
                }
            }
            if ($null -eq $BinaryPath) {
                Write-ErrorStatus "Error: Could not find $BinaryName binary in archive."
                Stop-Install
            }

            # A root this script creates is marked as ketch's, so `ketch self
            # uninstall` may empty it; one that already existed may hold other programs.
            $NewRoot = -not (Test-Path -LiteralPath $Root)
            $RootBin = Join-Path $Root 'bin'
            New-Item -ItemType Directory -Path $RootBin -Force | Out-Null
            if ($NewRoot) {
                Set-Content -LiteralPath (Join-Path $Root '.ketch-root') -Encoding ascii `
                    -Value 'ketch created this directory; `ketch self uninstall` removes it with everything inside.' `
                    -ErrorAction SilentlyContinue
            }
            $InstallPath = Join-Path $RootBin $BinaryName

            if (Test-Path -LiteralPath $InstallPath) {
                Write-Status 'Upgrading ketch...'
            }
            else {
                Write-Status 'Installing ketch...'
            }

            # Let ketch install itself. The downloaded binary is only used to run
            # `self install`, which fetches this same release again through ketch's own
            # pipeline: verified against SHA256SUMS, unpacked into the store, linked
            # from the bin dir and recorded like any other package.
            $selfInstall = @('self', 'install')
            if ($InstallDirExplicit) {
                New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
                # ketch records the bootstrap link on the package and removes it on uninstall.
                $selfInstall += @('--link-dir', $InstallDir)
            }
            & $BinaryPath @selfInstall
            if ($LASTEXITCODE -ne 0) {
                Write-ErrorStatus "Error: ketch could not install itself into $Root."
                Stop-Install
            }

            $pathSet = $false
            if (-not $NoModifyPath) {
                Write-Status 'Setting up PATH...'
                Add-UserPathEntry $RootBin | Out-Null
                $pathSet = $true
            }

            Write-Host ''
            Write-Host "[OK] ketch $Version installed successfully!" -ForegroundColor Green
            Write-Host ''
            Write-Host "Installed to: $InstallPath"
            Write-Host ''

            if ($pathSet) {
                Write-Host 'PATH updated. Open a new terminal to use ketch.'
            }
            else {
                Write-Host "To use ketch, add $RootBin to your PATH:"
                Write-Host "  $InstallPath path install" -ForegroundColor Green
            }

            Write-Host ''
            Write-Host 'Getting started:'
            & $InstallPath --help
            if ($LASTEXITCODE -ne 0) {
                Stop-Install $LASTEXITCODE
            }
        }
        finally {
            Pop-Location
            Remove-TempDir
        }
    }

    # KETCH_ROOT is how `self install` learns the root, but environment
    # variables are the session's, not this scriptblock's.
    $callerRoot = $env:KETCH_ROOT
    $code = 0
    try {
        Install-Ketch @args
    }
    catch {
        $code = $_.Exception.Data['KetchInstallExit']
        if ($null -eq $code) {
            Write-ErrorStatus "Error: $($_.Exception.Message)"
            $code = 1
        }
    }
    finally {
        $env:KETCH_ROOT = $callerRoot
    }
    if ($code -ne 0) {
        # Run as a file (`-File`, `.\install.ps1`), `exit` ends only the script and
        # its caller gets the status. Piped into `iex` there is no script to end.
        if ($PSCommandPath) {
            exit $code
        }
        $global:LASTEXITCODE = $code
    }
} @args
