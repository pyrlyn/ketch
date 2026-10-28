package ketch.core.selfupdate

import fs.createDirAll
import fs.isDir
import fs.joinPath
import ketch.core.config.SELF_REPO
import ketch.core.testing.withEnv
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OwnersTest {
    @Test
    fun aCaskroomDirectoryIsWhatSaysBrewInstalledKetch(): Unit =
        withTempDir { tmp ->
            val prefix = joinPath(tmp, "homebrew")
            withEnv(listOf("HOMEBREW_PREFIX"), mapOf("HOMEBREW_PREFIX" to prefix.toString())) {
                val standard = listOf("/opt/homebrew", "/usr/local").map { joinPath(joinPath(Path(it), "Caskroom"), SELF_NAME) }.firstOrNull(::isDir)
                assertEquals(standard, caskDir(), "no cask under the prefix means brew did not install ketch")

                val cask = joinPath(joinPath(prefix, "Caskroom"), SELF_NAME)
                createDirAll(cask)
                assertEquals(cask, caskDir())
            }
        }

    @Test
    fun aBinaryUnderMiseInstallsBelongsToThatToolDirectory() {
        val data = Path("/home/u/.local/share/mise")
        // Before and after the move from listepo to pyrlyn.
        for (tool in listOf("github-pyrlyn-ketch", "github-listepo-ketch")) {
            val exe = joinPath(data, "installs/$tool/0.4.7/ketch")
            assertEquals(joinPath(joinPath(data, "installs"), tool), miseToolDirIn(exe, listOf(Path("/elsewhere"), data)))
        }
    }

    @Test
    fun aBinaryOutsideMiseInstallsIsNotMises() {
        val data = Path("/home/u/.local/share/mise")
        for (exe in listOf("/home/u/.ketch/store/ketch/0.4.7/ketch", "/home/u/.local/share/mise/shims/ketch", "/home/u/.local/share/mise/installs")) {
            assertNull(miseToolDirIn(Path(exe), listOf(data)), exe)
        }
    }

    @Test
    fun aMiseInstallDirectoryMapsBackToTheToolNameUnuseTakes() {
        val cases =
            listOf(
                "github-pyrlyn-ketch" to "github:pyrlyn/ketch",
                "ubi-pyrlyn-ketch" to "ubi:pyrlyn/ketch",
                // Installed before the move: mise's config holds the old name.
                "github-listepo-ketch" to "github:listepo/ketch",
                "ubi-listepo-ketch" to "ubi:listepo/ketch",
                "ketch" to "ketch",
                "-pyrlyn-ketch" to "-pyrlyn-ketch",
                "-listepo-ketch" to "-listepo-ketch",
                "github-other-ketch" to "github-other-ketch",
            )
        for ((dir, tool) in cases) assertEquals(tool, miseToolName(joinPath(Path("/mise/installs"), dir), SELF_REPO), dir)
        // A fork is not renamed: only its own directory maps back.
        assertEquals("github:someone/ketch", miseToolName(Path("/mise/installs/github-someone-ketch"), "someone/ketch"))
        assertEquals("github-listepo-ketch", miseToolName(Path("/mise/installs/github-listepo-ketch"), "someone/ketch"))
    }

    @Test
    fun bothStandardPrefixesAreTriedWhenHomebrewHasNotSaidWhereItIs() {
        // Not asserted against the environment: the machine running the suite may well have a cask, and that is a
        // valid answer too.
        for (prefix in listOf(Path("/opt/homebrew"), Path("/usr/local"))) {
            val cask = joinPath(joinPath(prefix, "Caskroom"), SELF_NAME)
            assertEquals(cask.takeIf(::isDir), caskDirIn(listOf(prefix)))
        }
    }

    @Test
    fun homebrewPrefixDoesNotHideACaskUnderAnotherPrefix(): Unit =
        withTempDir { tmp ->
            val active = joinPath(tmp, "opt-homebrew")
            val other = joinPath(tmp, "usr-local")
            createDirAll(joinPath(active, "bin"))
            val cask = joinPath(joinPath(other, "Caskroom"), SELF_NAME)
            createDirAll(cask)
            // Active brew answered, but the cask lives under the other tree — the same shape as an Apple Silicon
            // HOMEBREW_PREFIX with a leftover Intel cask.
            assertEquals(cask, caskDirIn(listOf(active, other)))
        }

    @Test
    fun theBrewBesideTheCaskIsPreferredToTheOneOnPath(): Unit =
        withTempDir { tmp ->
            val prefix = joinPath(tmp, "homebrew")
            val cask = joinPath(joinPath(prefix, "Caskroom"), SELF_NAME)
            createDirAll(cask)
            // Nothing installed there yet, so there is no better answer than PATH.
            assertEquals("brew", brewBinary(cask))

            val brew = joinPath(joinPath(prefix, "bin"), "brew")
            createDirAll(joinPath(prefix, "bin"))
            brew.write("#!/bin/sh\n")
            assertEquals(brew.toString(), brewBinary(cask))
        }
}
