# The VM's half of run.sh. Gradle runs on a copy on the VM's own disk: it cannot lock files on a shared folder, and the two
# systems must not share build directories. The copy stays, so a second run is incremental.
param(
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$Packages,
    [Parameter(ValueFromRemainingArguments)][string[]]$GradleArgs
)

$work = 'C:\src\ketch-e2e'
$project = "$work\apps\ketch-kotlin"

function Mirror($from, $to) {
    robocopy $from $to /MIR /XD build .gradle .git .kotlin .rtok /NFL /NDL /NJH /NJS /NP | Out-Null
    # robocopy's exit codes below 8 all mean the copy succeeded.
    if ($LASTEXITCODE -ge 8) { throw "copying $from failed: $LASTEXITCODE" }
}

Mirror $Source $project
Mirror $Packages "$work\packages"
$linked = 'ketch\build\bin\mingwX64\debugExecutable'
New-Item -ItemType Directory -Force "$project\$linked" | Out-Null
Copy-Item "$Source\$linked\ketch.exe" "$project\$linked"

Set-Location $project
# The Mac lints the same sources, and shared-settings' ktlint filter misses generated code behind Windows path separators.
& .\gradlew.bat --no-daemon --console=plain :desktop:e2e -x :ketch:linkDebugExecutableMingwX64 -x ktlintCheck -x detekt @GradleArgs
$status = $LASTEXITCODE

foreach ($results in 'desktop\e2e\references\windows', 'desktop\build\e2e') {
    if (Test-Path $results) { Mirror "$project\$results" "$Source\$results" }
}
exit $status
