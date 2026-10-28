package ketch.core.source

import fs.setMode
import ketch.core.config.Config
import ketch.core.error.KetchError
import ketch.core.model.PackageRef
import ketch.core.report.Ctx
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SourceRegistryTest {
    @Test
    fun theBuiltinsAreFoundByAnyCaseOfTheirScheme(): Unit =
        withTempDir { tmp ->
            val report = Report.silent()
            val registry = SourceRegistry.builtinOnly(Ctx(Config.load(tmp, report), report))
            assertEquals(listOf("github", "local"), registry.schemes())
            assertEquals("github", registry.get("GitHub").scheme())
            assertEquals("local", registry.forRef(assertNotNull(PackageRef.parse("local:/tmp/x"))).scheme())
            assertFailsWith<KetchError.UnknownScheme> { registry.get("gitlab") }
        }
}
