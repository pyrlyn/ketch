// Local filesystem installs: archive, bare binary, symlink, missing path (Rust tests/local_install.rs). The `local`
// source never touches the network.
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package ketch.e2e

import fs.createDirAll
import fs.entryKind
import fs.symlink
import ketch.e2e.support.Entry
import ketch.e2e.support.Sandbox
import ketch.e2e.support.fails
import ketch.e2e.support.get
import ketch.e2e.support.items
import ketch.e2e.support.json
import ketch.e2e.support.makeExecutable
import ketch.e2e.support.ok
import ketch.e2e.support.resolve
import ketch.e2e.support.said
import ketch.e2e.support.str
import ketch.e2e.support.tarGz
import ketch.e2e.support.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalInstallTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanUp() = sandbox.close()

    private fun writeProgram(
        path: Path,
        says: String,
    ) {
        writeText(path, "#!/bin/sh\necho '$says'\n")
        makeExecutable(path)
    }

    /** A minimal `.app` bundle whose executable prints [says]. */
    private fun writeApp(
        app: Path,
        says: String,
    ) = writeProgram(app.resolve("Contents/MacOS/Thing"), says)

    private val isMac get() = Platform.osFamily == OsFamily.MACOSX

    private fun exists(path: Path) = SystemFileSystem.exists(path)

    private fun tinyArchive(): Path {
        val archive = sandbox.fixture("tiny-tool.tar.gz")
        tarGz(Entry.program("tiny-tool/bin/tinytool", "from-archive"), Entry.file("tiny-tool/README.md", "hi\n")).writeTo(archive)
        return archive
    }

    /** The file is staged under its sanitized name, so the link must look for the same spelling: a leading dot is one sanitizing strips. */
    @Test
    fun aLocalBinaryWhoseNameStartsWithADotStillLinks() {
        val fixture = sandbox.fixture(".dottool")
        writeProgram(fixture, "dotted")
        sandbox.ok("install", "--path", fixture.toString(), "-y")
        assertEquals("dotted", said(sandbox.bin.resolve("dottool")))
    }

    /** A bundle is hashed as a tree rather than downloaded, and that path has to be held to the lockfile exactly like a file is. */
    @Test
    fun syncRefusesALocalAppThatChangedSinceTheLock() {
        if (!isMac) return
        val app = sandbox.fixture("Thing.app")
        writeApp(app, "v1")
        val lock = sandbox.home.resolve("ketch.lock").toString()

        sandbox.ok("install", "--path", app.toString(), "-y")
        sandbox.ok("lock", "--file", lock)
        sandbox.ok("uninstall", "thing.app", "--yes")
        writeApp(app, "v2")

        val err = sandbox.fails("sync", "--file", lock)
        assertTrue("does not match the lockfile" in err, err)
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
    }

    /** A local path has no published checksum to require; the policy has to treat a bundle the same as a file. */
    @Test
    fun requireChecksumTreatsALocalAppLikeALocalBinary() {
        if (!isMac) return
        val tool = sandbox.fixture("btool")
        writeProgram(tool, "b")
        val app = sandbox.fixture("Thing.app")
        writeApp(app, "v1")
        sandbox.ok("install", "--require-checksum", "--path", tool.toString(), "-y")
        sandbox.ok("install", "--require-checksum", "--path", app.toString(), "-y")
    }

    @Test
    fun localBinaryInstallAppearsInListAndInfoJson() {
        // Outside the root's bin dir, so it cannot collide with the links ketch creates.
        val fixture = sandbox.fixture("localtool")
        writeProgram(fixture, "local-binary-1")
        sandbox.ok("install", "--path", fixture.toString(), "--name", "localtool", "-y")

        val state = sandbox.state()
        assertTrue("\"name\": \"localtool\"" in state, state)
        assertTrue("local:" in state, state)
        assertTrue("\"local_kind\": \"binary\"" in state, state)

        val info = sandbox.ok("info", "localtool", "--json")
        assertTrue("\"local_kind\": \"binary\"" in info, info)
        assertTrue("\"local_path\"" in info, info)

        val linked = sandbox.bin.resolve("localtool")
        assertTrue(exists(linked), "binary was not linked into bin")
        assertEquals("local-binary-1", said(linked))

        // outdated must not error on a tree that is only local packages
        val parsed = json(sandbox.ok("outdated", "--json"))
        assertEquals("ok", parsed["status"].str)
        assertTrue(parsed["outdated"].items.isEmpty())
        assertTrue(parsed["failed"].items.isEmpty())
    }

    @Test
    fun localArchiveInstallViaLocalScheme() {
        val archive = tinyArchive()
        sandbox.ok("install", "local:$archive", "--name", "tinytool", "-y")

        val state = sandbox.state()
        assertTrue("\"local_kind\": \"archive\"" in state, state)
        assertTrue("tinytool" in state, state)
        val linked = sandbox.bin.resolve("tinytool")
        assertTrue(exists(linked), "archive binary not linked")
        assertEquals("from-archive", said(linked))
    }

    @Test
    fun localSymlinkToBinaryRecordsSymlinkKind() {
        val target = sandbox.fixture("real-tool")
        val link = sandbox.fixture("link-tool")
        writeProgram(target, "via-symlink")
        symlink(target, link)
        sandbox.ok("install", "--path", link.toString(), "--name", "linktool", "-y")

        val state = sandbox.state()
        assertTrue("\"local_kind\": \"symlink\"" in state, state)
        assertEquals("via-symlink", said(sandbox.bin.resolve("linktool")))
    }

    @Test
    fun missingLocalPathErrorsClearly() {
        val err = sandbox.fails("install", "--path", sandbox.fixture("no-such-local-file").toString(), "-y")
        assertTrue("does not exist" in err || "local path" in err, err)
    }

    @Test
    fun localPlainDirectoryIsRefused() {
        val dir = sandbox.fixture("plain-dir")
        createDirAll(dir)
        val err = sandbox.fails("install", "--path", dir.toString(), "-y")
        assertTrue("directory" in err || "refusing" in err, err)
    }

    @Test
    fun uninstallLocalBinaryClearsLinksAndKeepsSource() {
        val fixture = sandbox.fixture("localtool")
        writeProgram(fixture, "local-binary-1")
        sandbox.ok("install", "--path", fixture.toString(), "--name", "localtool", "-y")
        assertTrue(exists(sandbox.bin.resolve("localtool")))

        sandbox.ok("uninstall", "localtool", "--yes")

        assertFalse(exists(sandbox.bin.resolve("localtool")))
        assertFalse(exists(sandbox.store.resolve("localtool")))
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
        assertTrue(exists(fixture), "uninstall must not delete the user's original binary")
        assertEquals("local-binary-1", said(fixture))
    }

    @Test
    fun uninstallLocalSymlinkClearsLinksAndKeepsOrigin() {
        val target = sandbox.fixture("real-tool")
        val link = sandbox.fixture("link-tool")
        writeProgram(target, "via-symlink")
        symlink(target, link)
        sandbox.ok("install", "--path", link.toString(), "--name", "linktool", "-y")
        assertTrue(exists(sandbox.bin.resolve("linktool")))

        sandbox.ok("uninstall", "linktool", "--yes")

        assertFalse(exists(sandbox.bin.resolve("linktool")))
        assertFalse(exists(sandbox.store.resolve("linktool")))
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
        assertTrue(exists(target), "uninstall must keep the symlink target")
        assertTrue(entryKind(link) != null, "uninstall must keep the user's install-source symlink")
        assertEquals("via-symlink", said(link))
    }

    @Test
    fun uninstallLocalArchiveClearsLinks() {
        val archive = tinyArchive()
        sandbox.ok("install", "local:$archive", "--name", "tinytool", "-y")
        assertTrue(exists(sandbox.bin.resolve("tinytool")))

        sandbox.ok("uninstall", "tinytool", "--yes")

        assertFalse(exists(sandbox.bin.resolve("tinytool")))
        assertFalse(exists(sandbox.store.resolve("tinytool")))
        assertTrue("nothing installed" in sandbox.ok("list", "local"))
        assertTrue(exists(archive), "uninstall must not delete the user's archive")
    }
}
