package ketch.core.platform

import fs.EntryKind
import fs.createDirAll
import fs.entryKind
import fs.joinPath
import ketch.core.error.KetchError
import ketch.core.model.LinkKind
import ketch.core.model.LinkRecord
import ketch.core.model.LinkRole
import ketch.core.report.Report
import ketch.core.testing.program
import ketch.core.testing.read
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Linux placement needs nothing of Linux, so it is tested on every Unix host. */
class LinuxPlatformTest {
    @Test
    fun placeSymlinksADiscoveredBinary() {
        withTempDir { tmp ->
            val payload = joinPath(tmp, "payload")
            program(joinPath(payload, "bin"), "rg", "#!/bin/sh\necho rg\n")
            val store = joinPath(tmp, "store/rg/1.0")
            val bin = joinPath(tmp, "bin")
            createDirAll(bin)

            val links = LinuxPlatform().place(placement(payload, store, bin, tmp))
            assertEquals(1, links.size)
            assertEquals(LinkKind.Symlink, links[0].kind)
            assertEquals(joinPath(bin, "rg"), links[0].link)
            assertEquals(EntryKind.Symlink, entryKind(joinPath(bin, "rg")))
        }
    }

    @Test
    fun placePrefersCapitalBinOverNoisySiblings() {
        withTempDir { tmp ->
            val payload = joinPath(tmp, "payload")
            program(joinPath(payload, "Bin"), "rg", "#!/bin/sh\necho rg\n")
            program(payload, "noise", "#!/bin/sh\necho noise\n")
            val store = joinPath(tmp, "store/rg/1.0")
            val bin = joinPath(tmp, "bin")
            createDirAll(bin)

            val links = LinuxPlatform().place(placement(payload, store, bin, tmp))
            assertEquals(1, links.size, "$links")
            assertEquals(joinPath(bin, "rg"), links[0].link)
            assertNull(entryKind(joinPath(bin, "noise")))
        }
    }

    @Test
    fun unplaceLeavesAFileTheUserPutWhereALinkWas() {
        withTempDir { tmp ->
            val target = program(joinPath(tmp, "store/pkg/1.0"), "tool")
            val bin = joinPath(tmp, "bin")
            createDirAll(bin)
            val link = joinPath(bin, "tool")
            symlink(target, link)
            val record = LinkRecord(link, target, LinkKind.Symlink, LinkRole.Binary)
            val p = LinuxPlatform()
            p.unplace(listOf(record), Report.silent())
            assertNull(entryKind(link))

            link.write("mine")
            p.unplace(listOf(record), Report.silent())
            assertEquals("mine", link.read())
        }
    }

    @Test
    fun occupiedDestinationThatIsNotOursIsLeftAlone() {
        withTempDir { tmp ->
            val payload = joinPath(tmp, "payload")
            program(joinPath(payload, "bin"), "tool", "#!/bin/sh\necho new\n")
            val store = joinPath(tmp, "store/pkg/1.0")
            val bin = joinPath(tmp, "bin")
            createDirAll(bin)
            joinPath(bin, "tool").write("mine")

            val err = assertFailsWith<KetchError> { LinuxPlatform().place(placement(payload, store, bin, tmp)) }
            assertTrue(err.message.orEmpty().contains("already exists"), err.message)
            assertEquals("mine", joinPath(bin, "tool").read())
        }
    }

    @Test
    fun verifyTrustIsNotApplicable() {
        withTempDir { tmp -> assertEquals(TrustVerdict.NotApplicable, LinuxPlatform().verifyTrust(tmp)) }
    }
}
