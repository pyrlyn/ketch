---
status: in progress
priority: P0
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T5. Archive extraction

`extract` (`mod`, `archive`, `macos`): tar, gzip, xz, bzip2, zip, `.dmg` and `.pkg` on macOS,
with every guard the Rust code has (path traversal, links out of the tree, size and member
limits, unsupported formats refused). Codecs come from maintained libraries or system C
libraries through cinterop; the choice is recorded in `plan/README.md`.

## Plan

About 2.5k lines of Rust and 52 tests (`mod.rs` 8, `archive.rs` 34, `macos.rs` 10), ported from
41738f64. Creator decisions: the C codecs come from Conan (`bzip2/1.0.8`, `xz_utils/5.8.4`,
pinned by a lockfile, static, ConanCenter prebuilt binaries); zlib is K/N's `platform.zlib`; the
format readers live in a new kotlin-kit module and the guards stay in ketch-core.

1. kotlin-kit `fs`: `symlink`, `setMode`, `createDir` (one level, refusing an existing entry).
2. kotlin-kit `archive` (package `archive`), behind its own API:
   - `conanfile.txt`, `conan.lock` and a host profile; a Gradle task runs `conan install` with
     the `direct_deploy` deployer into `build/conan/<target>`, and the cinterop for bzip2 and
     liblzma links the static libraries from there;
   - codecs as kotlinx-io `RawSource`s: gzip (one member, CRC checked), bzip2 (one stream),
     xz (concatenated streams, a memory limit that is its own error); gzip and bzip2 encoders;
   - `TarReader` (ustar, GNU long names and links, PAX `path`/`linkpath`/`size`, base-256
     numbers, header checksum, old GNU sparse expanded) and a `TarWriter`;
   - `ZipReader` (central directory, zip64, stored and deflate, CRC checked, unix and DOS
     modes as the `zip` crate reads them) and a `ZipWriter`.
3. ketch-core `extract`: `Extractor`, `extractAuto`, `Budget`, `readHead`, `resolvesInside`,
   `isBundleName`, `unwrapSingleDir`; the portable extractors with every guard (`walkInside`,
   `ensureParent`, link target checks, deferred links, directory modes, setuid dropped,
   program heads); `platform.ensureExecutable`.
4. ketch-core `extract.macos`: dmg through `hdiutil`, pkg through `pkgutil`, copies through
   `ditto`, links out of the payload refused.

Rust tests that build archives with `tar::Builder`, `flate2`, `bzip2` and `zip` use the kit's
writers and encoders; the xz fixtures are the Rust test's bytes. Check: `./gradlew build` here
and `./gradlew check` in kotlin-kit green after each step.
