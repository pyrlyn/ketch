package ketch.core.importer

/** Recorded responses, for tests: a URL not in the map is a 404. */
internal data class Recorded(
    val responses: Map<String, String> = emptyMap(),
) : Fetch {
    fun with(
        url: String,
        body: String,
    ) = Recorded(responses + (url to body))

    override fun text(url: String): String? = responses[url]
}
