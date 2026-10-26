package ketch.core.decide

import ketch.core.process.Occupant
import kotlinx.io.files.Path

/**
 * What the core asks a person: decisions the pipeline cannot infer.
 *
 * The pipeline never reads a terminal. Where inference runs out — which of several binaries sharing a package's
 * name to link, whether to stop a process that holds a file being replaced — it asks the decider its caller put
 * in the context. The CLI answers with a prompt, the desktop app with a dialog, a test from a script.
 * Confirmations a command makes before calling into the core (`remove`, `config reset`) are the front end's own.
 * Implementations must be safe to call from any thread; an answer may block on a person.
 */
interface Decider {
    /** The index of the pick among [candidates], the files of [pkg] sharing its name; null leaves it to the fixed rules. */
    fun chooseBinary(
        pkg: String,
        candidates: List<String>,
    ): Int?

    /** Whether to stop [occupants], processes that hold files about to be replaced. A decline leaves them running. */
    fun stopProcesses(occupants: List<Occupant>): Boolean

    /** Whether to remove [path], a package folder an earlier uninstall left behind with nothing recorded in it. */
    fun confirmRemoveLeftover(path: Path): Boolean
}

/** Answers nothing and declines everything, as a script would. */
object NoDecider : Decider {
    override fun chooseBinary(
        pkg: String,
        candidates: List<String>,
    ): Int? = null

    override fun stopProcesses(occupants: List<Occupant>) = false

    override fun confirmRemoveLeftover(path: Path) = false
}
