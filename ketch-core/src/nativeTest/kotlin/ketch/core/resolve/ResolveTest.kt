package ketch.core.resolve

import ketch.core.cancel.Cancel
import ketch.core.config.Config
import ketch.core.extract.Extractor
import ketch.core.model.Arch
import ketch.core.model.AssetSelector
import ketch.core.model.LinkRecord
import ketch.core.model.Manifest
import ketch.core.model.ManifestOrigin
import ketch.core.model.Os
import ketch.core.model.PackageRef
import ketch.core.model.PackageSpec
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.TargetSpec
import ketch.core.model.Version
import ketch.core.platform.AssetScore
import ketch.core.platform.DoctorCheck
import ketch.core.platform.Placement
import ketch.core.platform.Platform
import ketch.core.platform.scoreWindowsAsset
import ketch.core.report.Ctx
import ketch.core.report.ProgressSink
import ketch.core.report.Report
import ketch.core.source.ListOpts
import ketch.core.source.Source
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A platform that only scores; everything else is never reached by resolution. */
private open class FakePlatform(
    override val id: String = "fake",
    private val target: TargetSpec = TargetSpec(Os.MacOs, Arch.Aarch64),
) : Platform {
    override fun target() = target

    override fun scoreAsset(
        assetName: String,
        allowEmulation: Boolean,
    ): AssetScore? = if ("darwin" in assetName) AssetScore(50, Arch.Aarch64, false, "fake") else null

    override fun extractors(): List<Extractor> = emptyList()

    override fun place(plan: Placement): List<LinkRecord> = emptyList()

    override fun unplace(
        links: List<LinkRecord>,
        report: Report,
    ) = Unit

    override fun isExecutable(path: Path) = true

    override fun doctor(cfg: Config): List<DoctorCheck> = emptyList()
}

/** Stand-in for a native Windows host: real Windows scoring. */
private object WindowsX64Platform : FakePlatform("windows", TargetSpec(Os.Windows, Arch.X86_64)) {
    override fun scoreAsset(
        assetName: String,
        allowEmulation: Boolean,
    ) = scoreWindowsAsset(assetName, Arch.X86_64)
}

private fun asset(name: String) = ReleaseAsset(name, "https://example.invalid/$name?token=SECRET", size = 1, headers = mapOf("authorization" to "secret-token"))

private fun release(vararg names: String) = Release(Version.parse("1.0.0"), "v1.0.0", assets = names.map(::asset))

class ResolveTest {
    private fun <T> withConfig(
        target: TargetSpec = TargetSpec(Os.MacOs, Arch.Aarch64),
        body: (Config) -> T,
    ): T = withTempDir { tmp -> body(Config.load(tmp, Report.silent()).copy(target = target)) }

    @Test
    fun evaluateAssetsMatchesScoreAssetsAndKeepsRejects(): Unit =
        withConfig { cfg ->
            val rel = release("tool-linux.tar.gz", "tool-darwin.tar.gz")
            val eval = evaluateAssets(cfg, FakePlatform(), rel, AssetSelector())
            assertEquals(listOf("tool-darwin.tar.gz"), eval.scored.map { it.asset.name })
            assertEquals(listOf(RejectedAsset("tool-linux.tar.gz", "incompatible with macos-aarch64")), eval.rejected)
            assertEquals(eval.scored, scoreAssets(cfg, FakePlatform(), rel, AssetSelector()))
        }

    @Test
    fun aTargetPinIsTheOnlyScoredAsset(): Unit =
        withConfig { cfg ->
            val selector = AssetSelector(target = mapOf("macos-aarch64" to "*-mac-universal.zip"))
            val eval = evaluateAssets(cfg, FakePlatform(), release("tool-darwin.tar.gz", "tool-mac-universal.zip"), selector)
            assertEquals(
                listOf(RejectedAsset("tool-darwin.tar.gz", "does not match the pinned pattern `*-mac-universal.zip` for macos-aarch64")),
                eval.rejected,
            )
            assertEquals(listOf("tool-mac-universal.zip"), eval.scored.map { it.asset.name })
            assertEquals(Int.MAX_VALUE, eval.scored[0].score.score)
        }

    @Test
    fun excludeWinsOverIncludeAndOverTheTargetPin(): Unit =
        withConfig { cfg ->
            val selector = AssetSelector(listOf("*darwin*"), listOf("*.dmg"), mapOf("macos-aarch64" to "*.dmg"))
            val eval = evaluateAssets(cfg, FakePlatform(), release("tool-darwin.dmg", "tool-darwin.tar.gz"), selector)
            assertTrue(eval.scored.isEmpty())
            assertEquals(
                listOf(
                    RejectedAsset("tool-darwin.dmg", "excluded by `*.dmg`"),
                    RejectedAsset("tool-darwin.tar.gz", "does not match the pinned pattern `*.dmg` for macos-aarch64"),
                ),
                eval.rejected,
            )
        }

    @Test
    fun noCompatibleAssetLeavesEveryCandidateRejected(): Unit =
        withConfig { cfg ->
            val eval = evaluateAssets(cfg, FakePlatform(), release("tool-linux.tar.gz", "SHA256SUMS"), AssetSelector())
            assertTrue(eval.scored.isEmpty())
            assertEquals(
                listOf(
                    RejectedAsset("SHA256SUMS", "incompatible with macos-aarch64"),
                    RejectedAsset("tool-linux.tar.gz", "incompatible with macos-aarch64"),
                ),
                eval.rejected,
            )
        }

    @Test
    fun includePatternsRejectAssetsThatDoNotMatch(): Unit =
        withConfig { cfg ->
            val eval = evaluateAssets(cfg, FakePlatform(), release("tool-linux.tar.gz", "tool-darwin.tar.gz"), AssetSelector(include = listOf("*darwin*")))
            assertEquals(1, eval.scored.size)
            assertEquals(listOf(RejectedAsset("tool-linux.tar.gz", "does not match any include pattern")), eval.rejected)
        }

    @Test
    fun aTraceViewStripsControlTextAndDropsSecrets(): Unit =
        withConfig { cfg ->
            val rel = release("tool-darwin.tar.gz").let { it.copy(assets = listOf(it.assets[0].copy(name = "safe‮evil-darwin.tar.gz"))) }
            val best = evaluateAssets(cfg, FakePlatform(), rel, AssetSelector()).scored[0]
            val view = ScoredAssetView(safe(best.asset.name), best.score.score, safe(best.score.reason), false)
            assertFalse('‮' in view.name)
            val json = Json.encodeToString(ScoredAssetView.serializer(), view)
            for (secret in listOf("SECRET", "secret-token", "example.invalid", "authorization")) assertFalse(secret in json, json)
        }

    /** Patterns published for `rtok` in pyrlyn/ketch-registry (after the Windows zip was added to `include`). */
    private val rtokSelector =
        AssetSelector(
            include = listOf("*-apple-darwin.tar.xz", "*-linux-gnu.tar.xz", "*-pc-windows-msvc.zip"),
            exclude = listOf("*-update*", "source.tar.gz"),
        )

    /** Asset names from a real cargo-dist rtok release (v0.1.3 shape). */
    private val rtokRelease =
        release(
            "rtok-aarch64-apple-darwin.tar.xz",
            "rtok-aarch64-apple-darwin-update",
            "rtok-x86_64-unknown-linux-gnu.tar.xz",
            "rtok-x86_64-unknown-linux-gnu-update",
            "rtok-x86_64-pc-windows-msvc.zip",
            "rtok-x86_64-pc-windows-msvc-update",
            "source.tar.gz",
            "sha256.sum",
        )

    /** Regression: an include list of only darwin/linux .tar.xz made `ketch install rtok` fail on Windows. */
    @Test
    fun rtokRegistryIncludePicksWindowsMsvcZip(): Unit =
        withConfig(TargetSpec(Os.Windows, Arch.X86_64)) { cfg ->
            val eval = evaluateAssets(cfg, WindowsX64Platform, rtokRelease, rtokSelector)
            assertEquals(listOf("rtok-x86_64-pc-windows-msvc.zip"), eval.scored.map { it.asset.name })
        }

    @Test
    fun rtokRegistryIncludeWithoutWindowsLeavesWindowsEmpty(): Unit =
        withConfig(TargetSpec(Os.Windows, Arch.X86_64)) { cfg ->
            val selector = rtokSelector.copy(include = listOf("*-apple-darwin.tar.xz", "*-linux-gnu.tar.xz"))
            val eval = evaluateAssets(cfg, WindowsX64Platform, rtokRelease, selector)
            assertTrue(eval.scored.isEmpty(), "expected no Windows pick, got ${eval.scored.map { it.asset.name }}")
            assertTrue(
                eval.rejected.any { it.name == "rtok-x86_64-pc-windows-msvc.zip" && "does not match any include pattern" in it.reason },
                eval.rejected.toString(),
            )
        }

    @Test
    fun originTiersNameTheFourManifestSources() {
        assertEquals(
            listOf("user", "registry", "builtin", "inferred"),
            listOf(
                ManifestOrigin.User(Path("manifests/tool.toml")).tier,
                ManifestOrigin.Registry(Path("registry/tool/ketch.toml")).tier,
                ManifestOrigin.Builtin.tier,
                ManifestOrigin.Inferred.tier,
            ),
        )
    }

    @Test
    fun theChecksumPolicyFollowsWhatTheReleaseCarries(): Unit =
        withConfig { cfg ->
            assertEquals("not-applicable", checksumPolicy(cfg, null).policy)
            assertEquals("trust-on-first-use", checksumPolicy(cfg.copy(requireChecksums = false), asset("a")).policy)
            assertEquals("require", checksumPolicy(cfg.copy(requireChecksums = true), asset("a")).policy)
            val digested =
                asset("a").copy(
                    digest =
                        ketch.core.model.Checksum
                            .sha256("ab"),
                )
            assertEquals(ChecksumPolicy(cfg.requireChecksums, "sha256", "published-digest"), checksumPolicy(cfg, digested))
        }

    /** `why` resolves through the same call install makes, and a missing release is an answer, not an error. */
    @Test
    fun anExplanationNamesTheCandidateAndSurvivesAMissingRelease(): Unit =
        withConfig { cfg ->
            val cx = Ctx(cfg, Report.silent())
            val manifest = Manifest.inferred(assertNotNull(PackageRef.parse("github:o/tool")))
            val spec = PackageSpec.parse("o/tool")
            val source = FakeSource(listOf(release("tool-darwin.tar.gz", "tool-linux.tar.gz")))
            val trace = explainWith(cx, source, spec, manifest, ManifestOrigin.Inferred, FakePlatform())
            assertEquals("tool-darwin.tar.gz", trace.candidate?.name)
            assertEquals(SelectedRelease("v1.0.0", "1.0.0", false), trace.version.selected)
            assertEquals(ManifestStep("inferred", "inferred", "tool", "github:o/tool", spec.label()), trace.manifest)
            assertEquals("trust-on-first-use".takeUnless { cfg.requireChecksums } ?: "require", trace.checksum.policy)

            val empty = explainWith(cx, FakeSource(emptyList()), spec, manifest, ManifestOrigin.Inferred, FakePlatform())
            assertNull(empty.version.selected)
            assertNull(empty.candidate)
            assertEquals("not-applicable", empty.checksum.policy)
        }
}

private class FakeSource(
    private val releases: List<Release>,
) : Source {
    override fun scheme() = "github"

    override fun listReleases(
        id: String,
        opts: ListOpts,
    ) = releases

    override fun download(
        asset: ReleaseAsset,
        dest: Path,
        progress: ProgressSink,
        cancel: Cancel,
    ): String = error("why never downloads")
}
