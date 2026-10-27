package ketch.core.changelog

import fs.createDirAll
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChangelogTest {
    @Test
    fun aChangelogIsFoundWhereProjectsActuallyPutIt(): Unit =
        withTempDir { tmp ->
            val nested = Path(tmp, "share/doc/testtool")
            createDirAll(nested)
            Path(nested, "CHANGELOG.md").write("# Changelog\n")
            assertEquals(Path(nested, "CHANGELOG.md"), findFile(tmp))
        }

    @Test
    fun aPayloadWithNoChangelogReportsNone(): Unit =
        withTempDir { tmp ->
            Path(tmp, "README.md").write("# hi\n")
            assertNull(findFile(tmp))
        }

    @Test
    fun theRootIsPreferredOverACopyBuriedInDocs(): Unit =
        withTempDir { tmp ->
            createDirAll(Path(tmp, "docs"))
            Path(tmp, "CHANGELOG.md").write("root\n")
            Path(tmp, "docs/CHANGELOG.md").write("docs\n")
            assertEquals(Path(tmp, "CHANGELOG.md"), findFile(tmp))
        }

    @Test
    fun aFileIsReadSanitisedAndCutToTheVersion(): Unit =
        withTempDir { tmp ->
            val path = Path(tmp, "CHANGELOG.md")
            path.write("## 1.1.0\n- new‮\n\n## 1.0.0\n- old\n")
            assertEquals(Entry(Origin.File(path), "## 1.1.0", "- new"), fromFile(path, "v1.1.0"))
            assertEquals(Entry(Origin.File(path), null, "## 1.1.0\n- new\n\n## 1.0.0\n- old"), fromFile(path, "9.9.9"))
        }
}
