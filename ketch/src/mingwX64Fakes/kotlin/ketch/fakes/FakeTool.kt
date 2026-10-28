// A stand-in for a tool ketch hands work to (mise), for the Windows tests. Copied in as `<name>.exe`, it records its
// arguments in `<name>-args` beside itself and removes the path named in `<name>-removes`, as the real tool would.
package ketch.fakes

import fs.FsException
import fs.atomic
import fs.readText
import fs.removeAny
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.posix.fputs
import platform.posix.stderr
import process.currentExe
import kotlin.system.exitProcess

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val own = checkNotNull(currentExe()) { "cannot tell where this program is" }.removeSuffix(".exe")
    atomic(Path("$own-args"), args.joinToString(" ").encodeToByteArray())
    try {
        removeAny(Path(readText(Path("$own-removes")).trim()))
    } catch (e: FsException) {
        fputs("${Path(own).name}: ${e.message}\n", stderr)
        exitProcess(1)
    }
}
