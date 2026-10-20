package com.theveloper.pixelplay.data.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StreamHedgingTest {

    private fun config(hedge: String, schema: Int = 1) =
        """{"schema": $schema, "innertube": {"note": "x", "profiles": {"IOS": {"clientVersion": "21.26.4"}}, "hedge": $hedge}}"""

    @Test
    fun `the file as shipped keeps hedging off`() {
        assertNull(StreamHedging.parse(config("""{"enabled": false, "afterSeconds": 1.5, "strategyTimeoutSeconds": 8}""")))
    }

    @Test
    fun `enabled true turns it on with the given numbers`() {
        assertEquals(
            StreamHedging(afterMs = 2_000, strategyTimeoutMs = 6_000),
            StreamHedging.parse(config("""{"enabled": true, "afterSeconds": 2, "strategyTimeoutSeconds": 6}"""))
        )
    }

    @Test
    fun `missing numbers fall back to the defaults`() {
        assertEquals(StreamHedging(1_500, 8_000), StreamHedging.parse(config("""{"enabled": true}""")))
    }

    @Test
    fun `numbers are clamped like on iOS`() {
        assertEquals(
            StreamHedging(afterMs = 500, strategyTimeoutMs = 15_000),
            StreamHedging.parse(config("""{"enabled": true, "afterSeconds": 0.1, "strategyTimeoutSeconds": 99}"""))
        )
        assertEquals(
            StreamHedging(afterMs = 10_000, strategyTimeoutMs = 3_000),
            StreamHedging.parse(config("""{"enabled": true, "afterSeconds": 60, "strategyTimeoutSeconds": 1}"""))
        )
    }

    @Test
    fun `odd values fall back to the defaults`() {
        assertEquals(
            StreamHedging(1_500, 8_000),
            StreamHedging.parse(config("""{"enabled": true, "afterSeconds": "fast", "strategyTimeoutSeconds": null}"""))
        )
    }

    @Test
    fun `only the json boolean true enables it`() {
        assertNull(StreamHedging.parse(config("""{"enabled": "true"}""")))
        assertNull(StreamHedging.parse(config("""{"enabled": 1}""")))
        assertNull(StreamHedging.parse(config("""{"afterSeconds": 1}""")))
    }

    @Test
    fun `missing sections, a newer schema or broken json mean off`() {
        assertNull(StreamHedging.parse("""{"schema": 1, "innertube": {"profiles": {}}}"""))
        assertNull(StreamHedging.parse("""{"schema": 1}"""))
        assertNull(StreamHedging.parse(config("""{"enabled": true}""", schema = 2)))
        assertNull(StreamHedging.parse("""{"schema": 1, "innertube": """))
        assertNull(StreamHedging.parse("[]"))
        assertNull(StreamHedging.parse(""))
    }

    @Test
    fun `a file without a schema counts as schema 1`() {
        assertEquals(StreamHedging(1_500, 8_000), StreamHedging.parse("""{"innertube": {"hedge": {"enabled": true}}}"""))
    }
}
