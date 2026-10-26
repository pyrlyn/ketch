package ketch.wire

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HelloTest {
    @Test
    fun roundTrips() {
        val hello = Hello(PROTOCOL, "0.0.1")
        assertEquals(hello, Json.decodeFromString(Hello.serializer(), Json.encodeToString(Hello.serializer(), hello)))
    }
}
