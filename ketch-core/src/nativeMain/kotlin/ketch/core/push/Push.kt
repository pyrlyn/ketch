package ketch.core.push

import fs.FsException
import fs.canonicalize
import fs.readText
import ketch.core.error.KetchError
import ketch.core.error.fsKitAt
import ketch.core.model.Manifest
import ketch.core.model.normalizeName
import ketch.core.registry.PACKAGE_FILE
import ketch.core.registry.validateRegistryEntry
import ketch.core.text.isAsciiAlphanumeric
import ketch.core.tomlfile.Document
import kotlinx.io.files.Path

/*
 * `ketch registry push`: offering a package to the registry.
 *
 * A `ketch.toml` at a project's root is the same file a registry package folder holds, so contributing the package is
 * a matter of getting that file into `<name>/ketch.toml` of the registry as a pull request. This module does that
 * through the GitHub API alone — no `git`, no `gh` — so it works from any machine ketch itself runs on, with the same
 * token every other command uses.
 *
 * Someone with push access to the registry gets a branch on it directly; everyone else gets a fork, kept in step with
 * the registry before the branch is cut from it. Either way the branch is `ketch/<name>`, so pushing the same package
 * twice updates one pull request rather than opening a second.
 */

/** One package, as read from a project's `ketch.toml`. */
data class Proposal(
    /** The registry folder the file goes into. */
    val name: String,
    /** The file exactly as written: the registry gets the author's text, not a re-serialisation that would drop their comments and ordering. */
    val body: String,
    val manifest: Manifest,
)

/**
 * Read and validate a project's `ketch.toml`.
 *
 * `name` is optional, as it is in a registry folder: without it, the folder the file sits in names the package, which
 * is also how the registry will read the file once it lands.
 */
fun load(path: Path): Proposal {
    val what = path.toString()
    val body = fsKitAt(path) { readText(path) }
    val doc = Document.parse(body, what)
    val folder =
        try {
            canonicalize(path).parent?.name
        } catch (_: FsException) {
            null
        }
    val declared = doc.str("name")
    val name =
        declared ?: normalizeName(folder ?: throw KetchError.Msg("$what has no `name` and sits in no folder that could supply one"))
    if (declared == null) doc.setStr("name", name)
    val manifest = doc.deserialize<Manifest>()
    try {
        manifest.validate()
    } catch (e: KetchError) {
        throw KetchError.Parse(what, e.message.orEmpty(), e)
    }
    validateRegistryEntry(what, folder, declared, manifest)
    return Proposal(name, body, manifest)
}

/** What [open] needs to know about a repository. */
data class Repo(
    val defaultBranch: String,
    /** Whether the token may push to it directly, or must go through a fork. */
    val canPush: Boolean,
)

/** A file as the registry holds it. */
data class RemoteFile(
    /** The blob sha, which the contents API demands back before it will overwrite the file. */
    val sha: String,
    val text: String,
)

/**
 * The handful of GitHub operations [open] composes, all by `owner/repo`.
 *
 * An interface only so the sequence — fork or not, create or update, open or find — can be tested without github.com.
 * [GitHub] is the one real implementation.
 */
@Suppress("TooManyFunctions")
interface Api {
    /** null when the repository does not exist, or the token cannot see it. */
    fun repository(repo: String): Repo?

    /** Fork [repo] for the token's user, or return the fork that exists. */
    fun fork(repo: String): String

    /** Bring [branch] of [fork] up to the repository it was forked from. */
    fun syncFork(
        fork: String,
        branch: String,
    )

    /** The commit [branch] points at, or null when there is no such branch. */
    fun branch(
        repo: String,
        branch: String,
    ): String?

    fun createBranch(
        repo: String,
        branch: String,
        sha: String,
    )

    fun resetBranch(
        repo: String,
        branch: String,
        sha: String,
    )

    fun file(
        repo: String,
        branch: String,
        path: String,
    ): RemoteFile?

    /** Commit [text] at [path]; [replacing] is the sha of the file it overwrites. */
    @Suppress("LongParameterList")
    fun writeFile(
        repo: String,
        branch: String,
        path: String,
        message: String,
        text: String,
        replacing: String?,
    )

    /** The new pull request's URL, or null when [head] already has one open. */
    @Suppress("LongParameterList")
    fun openPull(
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
    ): String?

    /** The open pull request from [head] into [base]. A branch can carry pull requests into other bases; those are not this proposal's. */
    fun findPull(
        repo: String,
        head: String,
        base: String,
    ): String?
}

/** What [open] did. */
sealed interface Outcome {
    /** The registry already holds exactly this file; nothing to propose. */
    data object Unchanged : Outcome

    data class Opened(
        val pull: PullRequest,
    ) : Outcome
}

data class PullRequest(
    val url: String,
    /** True when the branch already had an open pull request, which the new commit simply joined. */
    val alreadyOpen: Boolean,
)

/** Put the proposal on a branch and open a pull request for it. */
fun open(
    api: Api,
    registry: String,
    proposal: Proposal,
): Outcome {
    val repo = api.repository(registry) ?: throw missingRegistry(registry)
    val base = repo.defaultBranch
    val headRepo = if (repo.canPush) registry else api.fork(registry).also { api.syncFork(it, base) }
    val baseSha = api.branch(registry, base) ?: throw KetchError.Msg("$registry has no branch $base")

    // Always branch from the registry's current tip. A branch left by an earlier push carries an earlier proposal and
    // nothing else worth keeping.
    val branch = branchFor(proposal.name)
    if (api.branch(headRepo, branch) != null) api.resetBranch(headRepo, branch, baseSha) else api.createBranch(headRepo, branch, baseSha)

    val path = "${proposal.name}/$PACKAGE_FILE"
    val existing = api.file(headRepo, branch, path)
    if (existing?.text == proposal.body) return Outcome.Unchanged
    val verb = if (existing == null) "add" else "update"
    api.writeFile(headRepo, branch, path, "$verb ${proposal.name}", proposal.body, existing?.sha)

    val head = "${headRepo.substringBefore('/')}:$branch"
    val title = "${verb.replaceFirstChar { it.uppercaseChar() }} ${proposal.name}"
    val opened = api.openPull(registry, head, base, title, pullRequestBody(proposal))
    return Outcome.Opened(opened?.let { PullRequest(it, alreadyOpen = false) } ?: openPullFor(api, registry, head, base))
}

/** The pull request a refused open means is already there. The commit is on the branch already, so finding it is the whole job left. */
private fun openPullFor(
    api: Api,
    registry: String,
    head: String,
    base: String,
): PullRequest {
    val url = api.findPull(registry, head, base) ?: throw KetchError.Msg("$registry refused the pull request for $head, and none is open")
    return PullRequest(url, alreadyOpen = true)
}

/**
 * The registry's current copy of `<name>/ketch.toml`, from its default branch.
 *
 * This is the canonical "current config" a local file is measured against: the text `ketch registry push` diffs and
 * gates its question on, and the text [open] will find on the registry tip once its branch is cut. null when the
 * registry holds no such file.
 */
fun current(
    api: Api,
    registry: String,
    name: String,
): RemoteFile? {
    val repo = api.repository(registry) ?: throw missingRegistry(registry)
    return api.file(registry, repo.defaultBranch, "$name/$PACKAGE_FILE")
}

/** What pushing a proposal against the registry's [current] copy would do. */
enum class Plan {
    /** The registry holds no copy of the package: the pull request adds it. */
    Add,

    /** The registry holds exactly this text: nothing to propose. */
    Unchanged,

    /** The registry holds a different text: the pull request updates it. */
    Update,
}

/** Decide [Plan] by exact text equality, mirroring the comparison [open] makes once its branch is cut — answered here before anything is sent. */
fun plan(
    current: RemoteFile?,
    proposal: Proposal,
): Plan =
    when {
        current == null -> Plan.Add
        current.text == proposal.body -> Plan.Unchanged
        else -> Plan.Update
    }

/** The complaint [open] and [current] share, worded once so the two cannot drift apart. */
private fun missingRegistry(registry: String) = KetchError.Msg("registry $registry does not exist, or the token cannot see it")

/**
 * The branch a proposal is pushed on, derived from the package name.
 *
 * `Manifest.validate` accepts names git refuses in a ref — spaces, `~`, `^`, `?`, `*`, `[`, `..`, a trailing
 * `.lock` — and a package name is a folder name, not a ref. Replacing those characters keeps one branch per package
 * and lets the pull request be opened instead of failing on a 422 nobody can read.
 */
internal fun branchFor(name: String): String {
    val safe =
        name
            .map { if (it.isAsciiAlphanumeric() || it in "-_.") it else '-' }
            .joinToString("")
            .replace("..", "-")
            .removeSuffix(".lock")
            .trim('.', '-')
    return if (safe.isEmpty()) "ketch/package" else "ketch/$safe"
}

private fun pullRequestBody(proposal: Proposal): String {
    val m = proposal.manifest
    val lines = mutableListOf("`${proposal.name}` from `${m.source}`.")
    m.description?.let { lines += listOf("", it) }
    m.homepage?.let { lines += listOf("", it) }
    lines += listOf("", "Opened with `ketch registry push`.")
    return lines.joinToString("\n")
}
