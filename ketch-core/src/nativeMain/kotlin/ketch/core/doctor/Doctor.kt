package ketch.core.doctor

import fs.FsException
import fs.canon
import fs.isDir
import fs.joinPath
import fs.listDir
import fs.readText
import ketch.core.config.Config
import ketch.core.config.HOST_WINDOWS
import ketch.core.config.envOs
import ketch.core.config.pathLookupKey
import ketch.core.config.splitPathVar
import ketch.core.error.KetchError
import ketch.core.log.Level
import ketch.core.platform.CheckStatus
import ketch.core.platform.DoctorCheck
import ketch.core.platform.extraDestinationChecks
import ketch.core.platform.host
import ketch.core.registry.agePhrase
import ketch.core.registry.exists
import ketch.core.registry.loadMeta
import ketch.core.report.Ctx
import ketch.core.selfupdate.SELF_NAME
import ketch.core.selfupdate.bootstrapBinaryName
import ketch.core.selfupdate.caskDir
import ketch.core.selfupdate.displayVersion
import ketch.core.selfupdate.staleAsideCheck
import ketch.core.shell.Setup
import ketch.core.shell.Shell
import ketch.core.shell.configuredIn
import ketch.core.shell.detect
import ketch.core.shell.installUser
import ketch.core.shell.isFile
import ketch.core.shell.pathCheck
import ketch.core.shell.staleRegistryCheck
import ketch.core.shell.userPathConfigured
import ketch.core.state.State
import ketch.core.state.processAlive
import ketch.core.text.bytes
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import ketch.core.registry.load as loadRegistry
import ketch.core.shell.install as installShell

/*
 * What `ketch doctor` checks: the machine, the install tree and the files ketch keeps, as a list of [DoctorCheck]s.
 * They read the install tree and nothing else, so a graphical front end gets the same list the CLI prints, and the
 * command keeps only `--fix` and the rendering.
 */

/**
 * Every check, in the order `ketch doctor` prints them.
 *
 * Never fails: a check that cannot run is itself a failed check, because the report exists to finish even when part
 * of the machine is broken.
 */
fun checks(cx: Ctx): List<DoctorCheck> {
    val cfg = cx.cfg
    val checks = mutableListOf(DoctorCheck.ok("version", "ketch ${displayVersion()} for ${cfg.target}"))

    // Not a platform check: every shell reads the same startup files wherever it runs, so a second platform would
    // only duplicate this.
    checks += pathCheck(cfg)
    staleRegistryCheck(cfg)?.let { checks += it }
    pathBinaryCheck(cfg)?.let { checks += it }
    staleAsideCheck(cfg)?.let { checks += it }

    // Rust's "unsupported platform" check has no counterpart: `host()` is chosen at compile time, so a build for an
    // operating system it does not know cannot exist.
    val host = host()
    checks += extraDestinationChecks(host)
    checks += host.doctor(cfg)
    checks += logCheck(cfg)
    checks += registryCheck(cx)
    checks += storeChecks(cfg)
    lockCheck(cfg)?.let { checks += it }
    return checks
}

/** How many checks failed outright; the command exits non-zero when any did. */
fun failed(checks: List<DoctorCheck>): Int = checks.count { it.status == CheckStatus.Fail }

/**
 * Repair what `doctor` can repair on its own, returning what changed.
 *
 * Only the PATH setup qualifies today: it needs no network and no choice from the user. Everything else doctor reports
 * either is already a one-line command or needs a decision ketch has no business making, and a fix that quietly
 * reinstalls packages would be a worse tool than one that says what to run.
 *
 * Failures are warnings on `cx.report` rather than errors: `doctor` exists to finish its report even when part of the
 * machine is broken, and one shell's unwritable startup file must not keep the others from being set up.
 */
fun fix(cx: Ctx): List<Setup> {
    val cfg = cx.cfg
    if (cfg.binDirOnPath() || configuredIn(cfg).isNotEmpty() || userPathConfigured(cfg)) return emptyList()
    // Windows keeps PATH in the registry, not in a startup file.
    return if (HOST_WINDOWS) fixUserPath(cx) else fixShellFiles(cx)
}

private fun fixShellFiles(cx: Ctx): List<Setup> =
    shellsToFix(cx).mapNotNull { sh ->
        try {
            Setup.ShellFile(installShell(cx.cfg, sh, false))
        } catch (e: KetchError) {
            cx.report.warn("${sh.shellName}: ${e.message}")
            null
        }
    }

private fun fixUserPath(cx: Ctx): List<Setup> =
    try {
        listOf(Setup.UserPath(installUser(cx.cfg, false)))
    } catch (e: KetchError) {
        cx.report.warn(e.message.orEmpty())
        emptyList()
    }

/** The shells `fix` sets up, or none after a warning saying why. */
private fun shellsToFix(cx: Ctx): List<Shell> {
    val shells =
        try {
            detect()
        } catch (e: KetchError) {
            cx.report.warn(e.message.orEmpty())
            return emptyList()
        }
    if (shells.isEmpty()) cx.report.warn("could not tell which shell you use; run `ketch path install --shell <name>`")
    return shells
}

/** Where this machine's log is, so nobody has to be told twice. */
private fun logCheck(cfg: Config): DoctorCheck {
    if (cfg.logLevel == Level.Off) return DoctorCheck.ok("log", "off")
    val size = SystemFileSystem.metadataOrNull(cfg.logFile)?.let { " · ${bytes(it.size)}" }.orEmpty()
    return DoctorCheck.ok("log", "${cfg.logFile} (${cfg.logLevel}, ${cfg.logFormat})$size")
}

internal fun registryCheck(cx: Ctx): DoctorCheck {
    val cfg = cx.cfg
    if (!exists(cfg)) return DoctorCheck.warn("registry", "no local copy of ${cfg.registry}", "Run `ketch update`.")
    val packages = "${loadRegistry(cx).size} packages from ${cfg.registry}"
    val (meta, problem) =
        try {
            loadMeta(cfg) to "fetch time unknown"
        } catch (e: KetchError) {
            null to e.message.orEmpty()
        }
    return if (meta == null) {
        DoctorCheck.warn("registry", "$packages; $problem", "Run `ketch update`.")
    } else {
        val source = meta.revision ?: meta.etag ?: "unknown revision"
        DoctorCheck.ok("registry", "$packages · ${agePhrase(meta.fetchedAt)} · $source")
    }
}

/** Everything ketch itself owns: the store matches the state file, and every link still points at the package that claims it. */
private fun storeChecks(cfg: Config): List<DoctorCheck> {
    val state =
        try {
            State.load(cfg)
        } catch (e: KetchError) {
            return listOf(DoctorCheck.fail("state", e.message.orEmpty(), "Inspect or remove ${cfg.stateFile}."))
        }

    val checks = mutableListOf<DoctorCheck>()
    val missingPayloads = mutableListOf<String>()
    val brokenLinks = mutableListOf<String>()
    for (pkg in state.iter()) {
        if (!SystemFileSystem.exists(pkg.prefix)) {
            // Its links cannot be sound either; one message per package is enough.
            missingPayloads += pkg.name
            continue
        }
        // `exists` follows symlinks, so this catches both a deleted link and one left dangling by a manual removal
        // inside the store.
        pkg.links.filterNot { SystemFileSystem.exists(it.link) }.forEach { brokenLinks += "${pkg.name} -> ${it.link}" }
    }

    checks +=
        if (missingPayloads.isEmpty()) {
            DoctorCheck.ok("packages", "${state.iter().size} installed")
        } else {
            DoctorCheck.fail(
                "packages",
                "${missingPayloads.size} packages have no files: ${missingPayloads.joinToString(", ")}",
                "Run `ketch install --force ${missingPayloads.joinToString(" ")}`.",
            )
        }

    if (brokenLinks.isNotEmpty()) {
        checks +=
            DoctorCheck.warn(
                "links",
                "${brokenLinks.size} broken links: ${brokenLinks.joinToString(", ")}",
                "Run `ketch link <pkg>` to recreate them.",
            )
    }

    val orphans = orphanStoreDirs(cfg.storeDir, state.names().toSet())
    if (orphans.isNotEmpty()) {
        checks +=
            DoctorCheck.warn(
                "orphans",
                "${orphans.size} prefixes have no state entry: ${orphans.joinToString(", ")}",
                "Inspect ${cfg.storeDir} and remove what you did not mean to keep.",
            )
    }

    leftoverCask(caskDir(), state.get(SELF_NAME) != null)?.let { checks += it }
    return checks
}

/** Store directories whose names are not an installed package. */
internal fun orphanStoreDirs(
    store: Path,
    known: Set<String>,
): List<String> {
    val names =
        try {
            listDir(store)
        } catch (_: FsException) {
            return emptyList()
        }
    return names.filter { !it.startsWith('.') && isDir(joinPath(store, it)) && it !in known }.sorted()
}

/** A Homebrew cask left behind after `ketch self uninstall` removed the rest. */
internal fun leftoverCask(
    cask: Path?,
    ketchInstalled: Boolean,
): DoctorCheck? {
    if (cask == null || ketchInstalled) return null
    return DoctorCheck.warn("cask", "Homebrew still has $cask", "Run `brew uninstall --cask ketch`.")
}

/** A `.lock` from a running ketch, or one a crashed run left behind. */
private fun lockCheck(cfg: Config): DoctorCheck? = lockCheckAt(cfg.lockFile, ::processAlive)

internal fun lockCheckAt(
    path: Path,
    alive: (Long) -> Boolean,
): DoctorCheck? {
    if (!SystemFileSystem.exists(path)) return null
    val holder =
        try {
            readText(path).trim().toUIntOrNull()?.toLong()
        } catch (_: FsException) {
            null
        }
    return when {
        holder == null -> DoctorCheck.warn("lock", "$path is unreadable", "Remove $path.")
        alive(holder) -> DoctorCheck.warn("lock", "held by pid $holder", "Wait for the other ketch to finish.")
        else -> DoctorCheck.warn("lock", "stale (pid $holder is gone)", "Remove $path.")
    }
}

/** The `ketch` (or `ketch.exe`) the store is supposed to expose on PATH. */
fun storeKetchLink(cfg: Config): Path = joinPath(cfg.binDir, bootstrapBinaryName())

/** Whether two paths name the same file, after resolving links. */
fun sameBinary(
    a: Path,
    b: Path,
): Boolean = pathLookupKey(canon(a).toString()) == pathLookupKey(canon(b).toString())

/** First `ketch`/`ketch.exe` on PATH, canonicalised when the file exists. */
fun firstKetchOnPath(): Path? = envOs("PATH")?.let(::firstKetchOnPathFrom)

private fun firstKetchOnPathFrom(path: String): Path? = splitPathVar(path).map { joinPath(Path(it), bootstrapBinaryName()) }.firstOrNull(::isFile)?.let(::canon)

/**
 * Warn when PATH finds a ketch that is not the store link.
 *
 * `cargo install ketch` (or a leftover bootstrap) often lands in `~/.cargo/bin` *ahead* of `~/.ketch/bin`. Then
 * `ketch --version` prints that old binary's version even though the store copy is newer. The PATH check only asks
 * whether the bin dir is present, not whether it wins.
 */
private fun pathBinaryCheck(cfg: Config): DoctorCheck? = envOs("PATH")?.let { pathBinaryCheckFrom(cfg, it) }

internal fun pathBinaryCheckFrom(
    cfg: Config,
    path: String,
): DoctorCheck? {
    val linked = storeKetchLink(cfg)
    val onPath = if (SystemFileSystem.exists(linked)) firstKetchOnPathFrom(path) else null
    if (onPath == null || sameBinary(onPath, linked)) return null
    return DoctorCheck.warn(
        "binary",
        "PATH runs $onPath before $linked",
        "Remove or rename the earlier copy (often ~/.cargo/bin/ketch from `cargo install`), or put ~/.ketch/bin first on PATH.",
    )
}
