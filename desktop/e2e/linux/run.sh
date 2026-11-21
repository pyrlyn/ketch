#!/bin/sh
# :desktop:e2e in the Linux image on Colima; arguments go to Gradle, so `run.sh -Pe2e.record` records. Kotlin/Native has no
# Linux arm64 host, so `ketch` is linked here, against the image's own compression libraries, and Gradle runs in the image
# on a copy: the two systems must not share build directories. The copy lives in a volume, so a second run is incremental.
set -eu
cd "$(dirname "$0")/../../.."
export DOCKER_CONTEXT=colima

packages=$(desktop/e2e/packages.sh)
libs=$PWD/build/e2e-linux/libs
image=ketch-e2e-linux

docker build -q -t "$image" desktop/e2e/linux >/dev/null

mkdir -p "$libs"
docker run --rm "$image" tar -C /usr/lib/aarch64-linux-gnu -ch libbz2.so.1.0 liblzma.so.5 | tar -C "$libs" -x
ln -sf libbz2.so.1.0 "$libs/libbz2.so"
ln -sf liblzma.so.5 "$libs/liblzma.so"
./gradlew --console=plain -q :ketch:linkDebugExecutableLinuxArm64 -Pdiffuser.allTargets=true \
    "-Pkotlin.native.linkArgs=-linker-option -L$libs -linker-option -lbz2 -linker-option -llzma -linker-option --allow-shlib-undefined"

mkdir -p desktop/build/e2e
docker run --rm \
    -v "ketch-e2e-linux:/work" \
    -v "$PWD:/src:ro" \
    -v "$packages:/packages:ro" \
    -v "$PWD/desktop/e2e/references:/out/references" \
    -v "$PWD/desktop/build/e2e:/out/e2e" \
    -e GRADLE_USER_HOME=/work/gradle \
    -e KETCH_EXE=/src/ketch/build/bin/linuxArm64/debugExecutable/ketch.kexe \
    "$image" sh -c '
        rsync -a --delete --filter=":- .gitignore" --exclude .git /src/ /work/ketch-kotlin/
        rsync -a --delete --filter=":- .gitignore" --exclude .git /packages/ /work/packages/
        cd /work/ketch-kotlin
        status=0
        # The configuration cache would keep the test task an old run'"'"'s X authority file.
        xvfb-run -a ./gradlew --no-daemon --no-configuration-cache --console=plain :desktop:e2e "$@" || status=$?
        if [ -d desktop/e2e/references/linux ]; then rsync -a desktop/e2e/references/linux /out/references/; fi
        if [ -d desktop/build/e2e ]; then rsync -a --delete desktop/build/e2e/ /out/e2e/; fi
        exit $status
    ' sh "$@"
