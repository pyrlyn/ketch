package ketch.core.push

import fs.createDir
import fs.joinPath
import ketch.core.error.KetchError
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import ketch.core.testing.write
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A registry in one class, recording what was asked of it. */
private data class Fake(
    val canPush: Boolean = true,
    val exists: Boolean = true,
    val branches: List<String> = emptyList(),
    val file: RemoteFile? = null,
    val pullAlreadyOpen: Boolean = false,
    /** The base the already-open pull request targets: `findPull` answers only when asked for that one. */
    val pullBase: String = "main",
) : Api {
    val calls = mutableListOf<String>()

    override fun repository(repo: String): Repo? {
        calls += "repository $repo"
        return if (exists) Repo("main", canPush) else null
    }

    override fun fork(repo: String): String {
        calls += "fork $repo"
        return "me/registry"
    }

    override fun syncFork(
        fork: String,
        branch: String,
    ) {
        calls += "sync_fork $fork $branch"
    }

    override fun branch(
        repo: String,
        branch: String,
    ): String? {
        calls += "branch $repo $branch"
        if (branch == "main") return "abc123"
        return if (branch in branches) "stale" else null
    }

    override fun createBranch(
        repo: String,
        branch: String,
        sha: String,
    ) {
        calls += "create_branch $repo $branch $sha"
    }

    override fun resetBranch(
        repo: String,
        branch: String,
        sha: String,
    ) {
        calls += "reset_branch $repo $branch $sha"
    }

    override fun file(
        repo: String,
        branch: String,
        path: String,
    ): RemoteFile? {
        calls += "file $repo $branch $path"
        return file
    }

    override fun writeFile(
        repo: String,
        branch: String,
        path: String,
        message: String,
        text: String,
        replacing: String?,
    ) {
        calls += "write_file $repo $branch $path '$message' ${replacing ?: "new"}"
    }

    override fun openPull(
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
    ): String? {
        calls += "open_pull $repo $head $base '$title'"
        return if (pullAlreadyOpen) null else "https://github.com/acme/registry/pull/1"
    }

    override fun findPull(
        repo: String,
        head: String,
        base: String,
    ): String? {
        calls += "find_pull $repo $head $base"
        // The pull request the head carries, offered only when it aims at the base asked for — a pull request into
        // another base is somebody else's.
        return if (base == pullBase) "https://github.com/acme/registry/pull/9" else null
    }
}

/** A registry the token cannot see, which is the one way `repository` answers null. */
private fun invisibleRegistry() = Fake(exists = false)

class PushTest {
    private fun proposal(): Proposal =
        withTempDir { dir ->
            // Project folder must match `name` — the same rule registry folders use.
            val project = joinPath(dir, "tool")
            createDir(project)
            val file = joinPath(project, "ketch.toml")
            file.write("name = \"tool\"\nsource = \"github:acme/tool\"\ndescription = \"A tool\"\n")
            load(file)
        }

    /** Write [text] as `<folder>/ketch.toml` in a scratch directory and hand it to [check]. */
    private fun <T> project(
        folder: String,
        text: String,
        check: (Path) -> T,
    ): T =
        withTempDir { dir ->
            val project = joinPath(dir, folder)
            createDir(project)
            val file = joinPath(project, "ketch.toml")
            file.write(text)
            check(file)
        }

    private fun failure(block: () -> Unit) = assertFailsWith<KetchError> { block() }.message.orEmpty()

    @Test
    fun aBranchNameIsDrawnFromAPackageNameGitWouldRefuse() {
        // Every one of these is a name `Manifest.validate` accepts and git refuses in a ref.
        for ((name, want) in listOf(
            "my tool" to "ketch/my-tool",
            "a~b^c?d*e[f" to "ketch/a-b-c-d-e-f",
            "dots..inside" to "ketch/dots-inside",
            "almost.lock" to "ketch/almost",
            "plain" to "ketch/plain",
        )) {
            assertEquals(want, branchFor(name), name)
        }
        for (name in listOf("my tool", "dots..inside", "almost.lock")) {
            val branch = branchFor(name)
            assertTrue(branch.none { it in " ~^?*[:" } && ".." !in branch && !branch.endsWith(".lock"), "$name -> $branch is not a ref git accepts")
        }
    }

    @Test
    fun theClientIsBuiltFromSynchronousCodeWithoutARunningRuntime() {
        // Rust's octocrab needed a runtime entered while its client was built; this client needs nothing, and this
        // proves building one from ketch's synchronous callers stays possible.
        GitHub.connect("token", Report.silent())
    }

    @Test
    fun aMaintainerBranchesTheRegistryItself() {
        val api = Fake()
        val out = open(api, "acme/registry", proposal())
        assertEquals(Outcome.Opened(PullRequest("https://github.com/acme/registry/pull/1", alreadyOpen = false)), out)
        assertEquals(
            listOf(
                "repository acme/registry",
                "branch acme/registry main",
                "branch acme/registry ketch/tool",
                "create_branch acme/registry ketch/tool abc123",
                "file acme/registry ketch/tool tool/ketch.toml",
                "write_file acme/registry ketch/tool tool/ketch.toml 'add tool' new",
                "open_pull acme/registry acme:ketch/tool main 'Add tool'",
            ),
            api.calls,
        )
    }

    @Test
    fun anOutsiderGoesThroughAForkKeptInStepWithTheRegistry() {
        val api = Fake(canPush = false)
        open(api, "acme/registry", proposal())
        val calls = api.calls
        assertTrue("fork acme/registry" in calls)
        assertTrue("sync_fork me/registry main" in calls)
        assertTrue("create_branch me/registry ketch/tool abc123" in calls)
        assertTrue("write_file me/registry ketch/tool tool/ketch.toml 'add tool' new" in calls)
        assertTrue("open_pull acme/registry me:ketch/tool main 'Add tool'" in calls)
    }

    @Test
    fun anExistingBranchIsResetToTheRegistryTipAndItsFileUpdated() {
        val api = Fake(branches = listOf("ketch/tool"), file = RemoteFile("filesha", "name = \"tool\"\nsource = \"github:acme/old\"\n"))
        open(api, "acme/registry", proposal())
        val calls = api.calls
        assertTrue("reset_branch acme/registry ketch/tool abc123" in calls)
        assertFalse(calls.any { it.startsWith("create_branch") })
        assertTrue("write_file acme/registry ketch/tool tool/ketch.toml 'update tool' filesha" in calls)
        assertTrue("open_pull acme/registry acme:ketch/tool main 'Update tool'" in calls)
    }

    @Test
    fun aFileTheRegistryAlreadyHasVerbatimOpensNothing() {
        val api = Fake(file = RemoteFile("filesha", proposal().body))
        assertEquals(Outcome.Unchanged, open(api, "acme/registry", proposal()))
        assertFalse(api.calls.any { it.startsWith("write_file") })
    }

    @Test
    fun aMissingCopyPlansAnAdd() {
        assertEquals(Plan.Add, plan(null, proposal()))
    }

    @Test
    fun aVerbatimCopyPlansNoChange() {
        assertEquals(Plan.Unchanged, plan(RemoteFile("filesha", proposal().body), proposal()))
    }

    @Test
    fun aDifferingCopyPlansAnUpdate() {
        assertEquals(Plan.Update, plan(RemoteFile("filesha", "name = \"tool\"\nsource = \"github:acme/old\"\n"), proposal()))
    }

    @Test
    fun currentReadsThePackageFileFromTheRegistrysDefaultBranch() {
        val registryFile = RemoteFile("filesha", "name = \"tool\"\nsource = \"github:acme/tool\"\n")
        val api = Fake(file = registryFile)
        assertEquals(registryFile, current(api, "acme/registry", "tool"))
        assertEquals(listOf("repository acme/registry", "file acme/registry main tool/ketch.toml"), api.calls)
    }

    @Test
    fun currentIsNoneWhenTheRegistryHoldsNoSuchFile() {
        assertNull(current(Fake(), "acme/registry", "tool"))
    }

    @Test
    fun anOversizedFileIsAnErrorRatherThanAnEmptyRead() {
        val error = failure { fileText("tool/ketch.toml", null, "sha") }
        assertTrue("tool/ketch.toml" in error, error)
        assertTrue("1 MB" in error, error)
    }

    @Test
    fun contentTheApiReturnedMapsToTheFile() {
        assertEquals(RemoteFile("sha", "name = \"tool\"\n"), fileText("tool/ketch.toml", "name = \"tool\"\n", "sha"))
    }

    @Test
    fun aMissingRegistryFailsCurrentTheWayItFailsOpen() {
        val fromCurrent = failure { current(invisibleRegistry(), "acme/registry", "tool") }
        val fromOpen = failure { open(invisibleRegistry(), "acme/registry", proposal()) }
        assertEquals(fromCurrent, fromOpen)
    }

    @Test
    fun aPullRequestAlreadyOpenIsFoundRatherThanDuplicated() {
        val api = Fake(pullAlreadyOpen = true)
        val out = open(api, "acme/registry", proposal())
        assertEquals(Outcome.Opened(PullRequest("https://github.com/acme/registry/pull/9", alreadyOpen = true)), out)
        assertTrue("find_pull acme/registry acme:ketch/tool main" in api.calls)
    }

    @Test
    fun aPullRequestIntoADifferentBaseIsNotReportedAsThisProposal() {
        // The branch already carries a pull request, but into `develop`; the registry's default branch is `main`, so
        // none of this proposal's exists even though a head-only lookup would find one.
        val api = Fake(pullAlreadyOpen = true, pullBase = "develop")
        val error = failure { open(api, "acme/registry", proposal()) }
        assertTrue("refused the pull request" in error, error)
        assertTrue("find_pull acme/registry acme:ketch/tool main" in api.calls)
    }

    @Test
    fun theFolderNamesThePackageWhenTheFileDoesNot() {
        val out = project("My-Tool.rs", "source = \"github:acme/tool\"\n", ::load)
        assertEquals("my-tool", out.name)
        assertEquals("my-tool", out.manifest.name)
        // The file itself is sent untouched.
        assertEquals("source = \"github:acme/tool\"\n", out.body)
    }

    @Test
    fun aNameThatWouldEscapeTheStoreIsRefused() {
        project("evil", "name = \"../evil\"\nsource = \"github:acme/tool\"\n") { file -> failure { load(file) } }
    }

    @Test
    fun aLocalSourceIsRefused() {
        val error = project("tool", "name = \"tool\"\nsource = \"local:/etc/passwd\"\n") { file -> failure { load(file) } }
        assertTrue("local path" in error, error)
    }

    @Test
    fun aDeclaredNameMustMatchItsFolder() {
        val error = project("fzf", "name = \"fzy\"\nsource = \"github:junegunn/fzf\"\n") { file -> failure { load(file) } }
        assertTrue("declares name" in error, error)
        assertTrue("fzy" in error, error)
        assertTrue("fzf" in error, error)
    }

    @Test
    fun anUnknownKeyIsRefusedBeforeAnythingIsSent() {
        project("tool", "name = \"tool\"\nsource = \"github:acme/tool\"\nbinary = \"tool\"\n") { file -> failure { load(file) } }
    }
}
