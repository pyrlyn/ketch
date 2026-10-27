package ketch.core.trust

import ketch.core.error.KetchError
import ketch.core.model.Provenance
import ketch.core.model.Release
import ketch.core.model.ReleaseAsset
import ketch.core.model.TrustMode
import ketch.core.model.TrustPolicy
import ketch.core.model.Verifier
import ketch.core.model.Version
import ketch.core.report.Report
import ketch.core.source.LocalSource
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rust's `without_verifiers` tests: what a ketch without signature checks does with a `trust` table. */
class VerifyTest {
    private fun asset(name: String) = ReleaseAsset(name, "https://example.invalid/$name", 1)

    private fun verifyMinisign(mode: TrustMode): Provenance? =
        withTempDir { dir ->
            val download = Path(dir, "tool.tar.gz")
            download.write("payload")
            val release =
                Release(Version.parse("1.0.0"), "v1.0.0", assets = listOf(asset("tool.tar.gz"), asset("tool.tar.gz.minisig")))
            val policy = TrustPolicy(Verifier.Minisign, mode, publicKey = "RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3")
            verify(policy, LocalSource(), release, release.assets[0], download, "0".repeat(64), dir, Report.silent())
        }

    @Test
    fun aRequiredSignatureRefusesTheInstall() {
        val refused = assertFailsWith<KetchError> { verifyMinisign(TrustMode.Require) }.message
        assertTrue("requires" in refused, refused)
        assertTrue("without signature verification" in refused, refused)
    }

    @Test
    fun aWarnPolicyInstallsOnTheChecksumAlone() {
        assertNull(verifyMinisign(TrustMode.Warn))
    }
}
