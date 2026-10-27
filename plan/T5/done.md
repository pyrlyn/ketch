# T5. Archive extraction — done

`extract` is ported: `Extractor`, `extractAuto`, `Budget`, `resolvesInside`, `unwrapSingleDir`,
the portable extractors (tar, tar.gz, tar.xz, tar.bz2, zip, lone .gz/.xz/.bz2 files, raw
binaries) with every guard of `archive.rs`, and on macOS the `.dmg` (`hdiutil`) and `.pkg`
(`pkgutil`) extractors that copy through `ditto` and refuse links out of the payload. The
readers, writers and codecs are kotlin-kit's new `archive` module (bzip2 and liblzma from Conan,
zlib from `platform.zlib`); the choice and the differences from Rust are in `plan/README.md`
under Decisions.

Registering `DmgExtractor` and `PkgExtractor` in front of the portable ones is
`platform/macos.rs`, ported with the platform layer.

Commits: kotlin-kit 8db81fb, ab99bcc, 9a281f5 (fs), 3a6ed36 (archive); ketch-kotlin a59c401,
fc3db73, f3f5108, f050fdf and this close.

Checked on the Mac: 51 of the 52 Rust tests are ported and pass (`mod.rs` 8, `archive.rs` 33,
`macos.rs` 10). `an_archive_whose_codec_is_left_out_is_unsupported` is not ported: it only
compiles in Rust when a codec feature is off, and every codec is always built in here.
`./gradlew build` (325 native tests, ktlint, detekt) is green, `./gradlew check` in kotlin-kit
is green, and `-Pdiffuser.allTargets=true :ketch-core:compileTestKotlinLinuxArm64` compiles.
