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
    @Suppress("UNUSED_PARAMETER") jobs: Int,
): List<Result<Installed>> = reqs.map { req -> outcome { install(cx, sources, state, req) } }

/** A package's failure is its own result; anything else is a bug and stops the batch. */
private inline fun outcome(block: () -> Installed): Result<Installed> =
    try {
        Result.success(block())
    } catch (e: KetchError) {
        Result.failure(e)
    }
