package ketch.core.releases

import ketch.core.info.wantsPrerelease
import ketch.core.manifest.sameSource
import ketch.core.model.Channel
import ketch.core.model.InstalledPackage
import ketch.core.model.Manifest
import ketch.core.report.Ctx
import ketch.core.source.ListOpts
import ketch.core.source.SourceRegistry

/**
 * The releases of [manifest]'s source, newest first as the source gave them, at most [limit] of them.
 *
 * [prerelease] is the caller's own `--pre`; the package's usual rules widen it (`config.toml`, the manifest, a `pre`
 * install) the way `info` and `outdated` already do. [draft] is the caller's `--draft`, and a `draft` install of this
 * source asks for drafts without it, as `outdated` does; a source without drafts refuses, and GitHub names a missing
 * token or push access. An installed package is marked only when it came from this source, since a tag means nothing in
 * another one.
 */
fun list(
    cx: Ctx,
    sources: SourceRegistry,
    manifest: Manifest,
    installed: InstalledPackage?,
    prerelease: Boolean,
    draft: Boolean,
    limit: Int,
): List<Entry> {
    val ours = installed?.takeIf { sameSource(it.source, manifest.source) }
    val source = sources.forRef(manifest.source)
    val opts =
        ListOpts(includePrerelease = wantsPrerelease(cx.cfg, manifest, ours, prerelease), limit = limit).let {
            if (draft || ours?.channel == Channel.Draft) it.askForDrafts(source, manifest.source.scheme) else it
        }
    return source.listReleases(manifest.source.id, opts).take(limit).map { Entry.of(it, ours) }
}
