// Read-only commands about what ketch has done: `history` and `stats` from src/cmd/query.rs.
package ketch.cmd

import cli.Matches
import ketch.core.config.Config
import ketch.core.log.timestamp
import ketch.core.stats.Event
import ketch.core.stats.Summary
import ketch.core.text.oneDecimal
import ketch.ui.out
import ketch.ui.table
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ketch.core.stats.history as recorded
import ketch.core.stats.summary as summarize

/** Recorded package history, for one package or the whole tree, newest first. */
fun history(
    cfg: Config,
    args: Matches,
) {
    val pkg = args.value("package")
    val limit = checkNotNull(args.value("limit")).toLong()
    val events = recorded(cfg, pkg, limit)
    when {
        args.flag("json") -> {
            printJson(JsonArray(events.map(::eventJson)))
        }

        events.isNotEmpty() -> {
            table(
                listOf("when", "package", "action", "version"),
                events.map { e -> listOf(timestamp(e.at), e.packageName, e.action, e.previousVersion?.let { "$it → ${e.version}" } ?: e.version) },
            )
        }

        // LIMIT 0 is a deliberate "show me nothing". An empty result there does not mean the database is empty.
        limit == 0L -> {}

        // Distinguish "this package has no history" from "nothing does": the first is a typo often enough to be worth
        // saying out loud.
        pkg != null -> {
            out("no history recorded for $pkg")
        }

        else -> {
            out("no history recorded yet")
        }
    }
}

/** An [Event] as serde writes the derived struct: its fields in declaration order, not sorted. */
private fun eventJson(e: Event) =
    JsonObject(
        linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(e.id),
            "package" to JsonPrimitive(e.packageName),
            "action" to JsonPrimitive(e.action),
            "version" to JsonPrimitive(e.version),
            "previous_version" to JsonPrimitive(e.previousVersion),
            "tag" to JsonPrimitive(e.tag),
            "source" to JsonPrimitive(e.source),
            "target" to JsonPrimitive(e.target),
            "asset_name" to JsonPrimitive(e.assetName),
            "sha256" to JsonPrimitive(e.sha256),
            "checksum_verified" to JsonPrimitive(e.checksumVerified),
            "duration_ms" to JsonPrimitive(e.durationMs),
            "at" to JsonPrimitive(e.at),
            "ketch_version" to JsonPrimitive(e.ketchVersion),
        ),
    )

/** Aggregate statistics over the recorded events. */
fun stats(
    cfg: Config,
    args: Matches,
) {
    val s = summarize(cfg)
    when {
        args.flag("json") -> {
            printJson(
                jsonOf(
                    "events" to JsonPrimitive(s.events),
                    "installs" to JsonPrimitive(s.installs),
                    "upgrades" to JsonPrimitive(s.upgrades),
                    "uninstalls" to JsonPrimitive(s.uninstalls),
                    "packages" to JsonPrimitive(s.packages),
                    "mean_duration_ms" to JsonPrimitive(s.meanDurationMs()),
                    "first_at" to JsonPrimitive(s.firstAt),
                    "last_at" to JsonPrimitive(s.lastAt),
                ),
            )
        }

        s.events == 0L -> {
            out("no statistics recorded yet")
        }

        else -> {
            table(listOf("statistic", "value"), statsRows(s))
        }
    }
}

private fun statsRows(s: Summary) =
    buildList {
        add(listOf("events", s.events.toString()))
        add(listOf("packages", s.packages.toString()))
        add(listOf("installs", s.installs.toString()))
        add(listOf("upgrades", s.upgrades.toString()))
        add(listOf("uninstalls", s.uninstalls.toString()))
        s.meanDurationMs()?.let { add(listOf("mean install", "${oneDecimal(it / MS_PER_SECOND)}s")) }
        s.firstAt?.let { add(listOf("first", timestamp(it))) }
        s.lastAt?.let { add(listOf("latest", timestamp(it))) }
    }

private const val MS_PER_SECOND = 1000.0
