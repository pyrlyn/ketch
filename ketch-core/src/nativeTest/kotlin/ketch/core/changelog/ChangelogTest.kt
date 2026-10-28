package ketch.core.changelog

import fs.createDirAll
import fs.joinPath
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
            val nested = joinPath(tmp, "share/doc/testtool")
            createDirAll(nested)
            joinPath(nested, "CHANGELOG.md").write("# Changelog\n")
            assertEquals(joinPath(nested, "CHANGELOG.md"), findFile(tmp))
        }

    @Test
    fun aPayloadWithNoChangelogReportsNone(): Unit =
        withTempDir { tmp ->
            joinPath(tmp, "README.md").write("# hi\n")
            assertNull(findFile(tmp))
        }

    @Test
    fun theRootIsPreferredOverACopyBuriedInDocs(): Unit =
        withTempDir { tmp ->
            createDirAll(joinPath(tmp, "docs"))
            joinPath(tmp, "CHANGELOG.md").write("root\n")
            joinPath(tmp, "docs/CHANGELOG.md").write("docs\n")
            assertEquals(joinPath(tmp, "CHANGELOG.md"), findFile(tmp))
        }

    @Test
    fun aFileIsReadSanitisedAndCutToTheVersion(): Unit =
        withTempDir { tmp ->
            val path = joinPath(tmp, "CHANGELOG.md")
            path.write("## 1.1.0\n- new‮\n\n## 1.0.0\n- old\n")
            assertEquals(Entry(Origin.File(path), "## 1.1.0", "- new"), fromFile(path, "v1.1.0"))
            assertEquals(Entry(Origin.File(path), null, "## 1.1.0\n- new\n\n## 1.0.0\n- old"), fromFile(path, "9.9.9"))
        }
}
