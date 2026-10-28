package ketch.core.state

import fs.atomic
import ketch.core.error.KetchError
import ketch.core.report.Report
import ketch.core.testing.withTempDir
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LockUnixTest {
    @Test
    fun aLockHeldByAnotherUsersProcessIsNotStolen(): Unit =
        withTempDir { dir ->
            val path = Path(dir, "lock")
            // pid 1 is running and is not ours to signal — the case that reads as "process is gone" if aliveness is
            // judged by `kill(pid, 0)` alone.
            atomic(path, "1".encodeToByteArray())
            assertFailsWith<KetchError.Busy> { Lock.acquirePath(path, Report.silent()) }
        }
}
