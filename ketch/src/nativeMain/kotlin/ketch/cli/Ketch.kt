package ketch.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.versionOption
import ketch.core.VERSION

class Ketch : CliktCommand(name = "ketch") {
    init {
        versionOption("$VERSION · preview", names = setOf("-V", "--version"), message = { "ketch $it" })
    }

    override fun help(context: Context) = "Catch releases straight from GitHub."

    override fun run() = Unit
}
