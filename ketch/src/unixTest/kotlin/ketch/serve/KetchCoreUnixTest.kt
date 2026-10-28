// The [KetchCore] cases Rust keeps to `cfg(unix)`: their source plugin is a shell script, and the doctor fix would
// otherwise write the real user PATH.

package ketch.serve

import fs.atomic
import fs.createDirAll
import fs.joinPath
import fs.readText
import fs.setMode
import ketch.core.listing.Available
import ketch.core.listing.Row
import ketch.core.listing.fillLatest
import ketch.core.manifest.Resolver
import ketch.core.model.PackageSpec
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.source.SourceRegistry
import ketch.wire.ChangelogSource
import ketch.wire.PathOutcome
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KetchCoreUnixTest {
    /**
     * A `test:` source plugin in [dir]'s root serving `alpha`'s releases, each with notes, and a user manifest naming
     * it — enough for a search and a changelog, which need no download.
     */
    private fun publishAlpha(
        dir: Path,
        versions: List<String>,
    ) {
        val cfg = cfg(dir)
        val releases = versions.map { v -> """{"version":"$v","tag":"v$v","notes":"notes for $v","assets":[]}""" }
        val feed = joinPath(dir, "alpha.releases.json")
        atomic(feed, "[${releases.joinToString(",")}]".encodeToByteArray())
        createDirAll(cfg.pluginDir)
        val plugin = joinPath(cfg.pluginDir, "ketch-source-test")
        val script =
            "#!/bin/sh\ncase \"\$1\" in\n" +
                "capabilities) printf '%s' '{\"protocol\":1,\"scheme\":\"test\",\"download\":false,\"search\":false}' ;;\n" +
                "describe) printf 'null' ;;\n" +
                "releases) cat '$feed' ;;\n" +
                "search) printf '[]' ;;\n" +
                "*) exit 1 ;;\nesac\n"
        atomic(plugin, script.encodeToByteArray())
        setMode(plugin, "755".toInt(8))
        createDirAll(cfg.manifestDir)
        atomic(
            joinPath(cfg.manifestDir, "alpha.toml"),
            "name = \"alpha\"\nsource = \"test:alpha\"\ndescription = \"the alpha tool\"\n".encodeToByteArray(),
        )
    }

    @Test
    fun aChangelogRangeIsEveryReleaseBetweenNewestFirst() =
        scratch { dir, core ->
            publishAlpha(dir, listOf("1.0.0", "1.1.0", "1.2.0", "1.3.0"))
            val range = core.changelogRange("alpha", "1.0.0", "1.2.0")
            assertEquals(listOf("1.2.0", "1.1.0"), range.map { it.version })
            assertEquals("notes for 1.2.0", range[0].body)
            assertEquals(ChangelogSource.Release, range[0].source)
            assertEquals(4, core.changelogRange("alpha", null, null).size)
        }

    @Test
    fun aSearchResultCarriesTheLatestAnEarlierListingCached() =
        scratch { dir, core ->
            publishAlpha(dir, listOf("1.0.0", "2.0.0"))
            // A limit of one leaves no room for repositories, so no source is searched over the network.
            assertNull(core.search("alpha", 1).known[0].latest)

            val cfg = cfg(dir)
            cfg.ensureDirs()
            val cx = Ctx(cfg, Report.silent())
            val (manifest, _) = Resolver.load(cx).resolve(PackageSpec.parse("alpha"))
            fillLatest(cx, SourceRegistry.load(cx), listOf(Row.offered(Available.fromManifest(cfg, manifest))))

            val after = core.search("alpha", 1)
            assertEquals("alpha", after.known[0].name)
            assertEquals("2.0.0", after.known[0].latest)
        }

    @Test
    fun doctorFixSetsUpTheShellInUseOnce() =
        scratch { dir, core ->
            val home = joinPath(dir, "home")
            createDirAll(home)
            // Every variable that could lead the fix to a real startup file.
            val env = mapOf("HOME" to home.toString(), "SHELL" to "/bin/zsh", "ZDOTDIR" to null, "XDG_CONFIG_HOME" to null)
            withEnv(env) {
                val fixed = core.doctorFix()
                assertEquals(1, fixed.size, fixed.toString())
                assertEquals("zsh", fixed[0].target)
                assertEquals(PathOutcome.Added, fixed[0].outcome)
                val file = joinPath(home, ".zshrc")
                assertEquals(file.toString(), fixed[0].file)
                assertTrue("ketch" in readText(file))
                assertEquals(emptyList(), core.doctorFix())
            }
        }
}
