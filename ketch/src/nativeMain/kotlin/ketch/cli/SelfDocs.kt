// The renderers for ketch's own man pages and completion scripts: the port of src/self_docs.rs.
//
// `ketch self install` places them beside the binary it installs. Writing and linking them is the core's job, but
// both are rendered from the command line, which only this module has, so it hands the renderers over.
package ketch.cli

import ketch.core.extra.SelfDocs
import cli.CompletionShell as ScriptShell

/** The renderers every `ketch self` path that places ketch's docs is given. */
internal val SELF_DOCS =
    SelfDocs(
        manPages = ::writeTo,
        completion = { shell -> script(ScriptShell.entries.first { it.id == shell.asStr }).encodeToByteArray() },
    )
