package ketch.core.install

import ketch.core.error.KetchError
import ketch.core.report.Ctx
import ketch.core.source.SourceRegistry
import ketch.core.state.State

/**
 * Install several packages. One result per request, in request order; a package that fails does not stop the rest.
 *
 * Rust downloads up to [jobs] packages at once. Here they go one at a time whatever [jobs] says: on Apple,
 * kotlinx-io's `Path.name` and `Path.parent` call `basename(3)` and `dirname(3)`, which return a shared static
 * buffer, so two threads preparing packages side by side read each other's file names.
 */
fun batch(
    cx: Ctx,
    sources: SourceRegistry,
    state: State,
    reqs: List<InstallRequest>,
    jobs: Int,
): List<Result<Installed>> {
    if (jobs <= 1 || reqs.size <= 1) return reqs.map { req -> outcome { install(cx, sources, state, req) } }
    // Rust's workers prepare against the state as it was before the batch and only `commit` reads the live one, so a
    // package asked for twice is placed twice rather than refused as already installed. One at a time, that snapshot
    // is what keeps `--jobs` meaning the same thing here.
    val snapshot = state.snapshot()
    return cx.report.batch().use { bars ->
        reqs.map { req -> outcome { bars.download(req.spec.label()).use { commit(cx, state, prepare(cx, sources, snapshot, req, it)) } } }
    }
}

/** A package's failure is its own result; anything else is a bug and stops the batch. */
private inline fun outcome(block: () -> Installed): Result<Installed> =
    try {
        Result.success(block())
    } catch (e: KetchError) {
        Result.failure(e)
    }
