#!/bin/sh
# :desktop:e2e in the Parallels Windows VM; arguments go to Gradle, so `run.sh -Pe2e.record` records. `ketch` is linked here,
# which the VM could do too, only much slower under its x64 emulation. run.ps1 is the VM's half.
set -eu
cd "$(dirname "$0")/../../.."

# The VM sees the Mac's ~/GitHub as a shared folder. prlctl reparses the line it is given, so nothing here may hold a space.
on_share() { printf '\\\\Mac\\GitHub%s' "$(printf %s "${1#"$HOME/GitHub"}" | tr / '\\')"; }

packages=$(desktop/e2e/packages.sh)
./gradlew --console=plain -q :ketch:linkDebugExecutableMingwX64
prlctl exec "Windows 11" --current-user powershell -NoProfile -ExecutionPolicy Bypass -File \
    "$(on_share "$PWD/desktop/e2e/windows/run.ps1")" "$(on_share "$PWD")" "$(on_share "$packages")" "$@"
