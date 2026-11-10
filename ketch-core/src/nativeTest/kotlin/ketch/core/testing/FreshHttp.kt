package ketch.core.testing

import http.HttpSettings
import http.ktor.KtorHttp
import ketch.core.http.Http
import ketch.core.report.Report

/**
 * An [Http] on its own client, closed afterwards. Tests that stop a stream mid-body can leave the
 * process-wide Curl client (Linux) unable to make the next request.
 */
fun <T> withFreshHttp(body: (Http) -> T): T {
    val client = KtorHttp.FACTORY.create(HttpSettings(15_000L, Long.MAX_VALUE, 120_000L))
    try {
        return body(Http(null, Report.silent(), client = client))
    } finally {
        client.close()
    }
}
