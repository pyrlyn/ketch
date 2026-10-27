package ketch.core.wizard

import ketch.core.model.BinSpec
import ketch.core.model.PackageKind

/** Every field answered, in the shapes the questionnaire collects them. */
internal val fullAnswers =
    Answers(
        source = "github:BurntSushi/ripgrep",
        // Typed the way a person types it; the manifest spells it the way ketch installs it.
        name = "RipGrep.rs",
        description = "Recursively search directories for a regex pattern",
        homepage = "https://github.com/BurntSushi/ripgrep",
        kind = PackageKind.Binary,
        prerelease = true,
        stripPrefix = 1uL,
        provides = listOf("rg"),
        notes = "Shell completions are under complete/ in the payload.",
        bin = listOf(BinSpec(path = "*/rg", name = "rg")),
        extraPaths = listOf("complete/rg.bash", "doc/rg.1"),
        assetInclude = listOf("*-apple-darwin.tar.gz"),
        assetExclude = listOf("*-musl-*"),
        assetTarget = mapOf("macos-x86_64" to "*-x86_64-apple-darwin.tar.gz", "macos-aarch64" to "*-aarch64-apple-darwin.tar.gz"),
    )

internal fun minimalAnswers(
    name: String,
    source: String,
) = Answers(source = source, name = name, bin = listOf(BinSpec(name = name)))
