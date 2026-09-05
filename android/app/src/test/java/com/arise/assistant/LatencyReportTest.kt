package com.arise.assistant

import com.arise.assistant.engine.LatencyReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyReportTest {
    @Test
    fun segmentsAndTotalsAreComputed() {
        val r = LatencyReport()
        r.mark("start")
        Thread.sleep(8)
        r.mark("wake")
        Thread.sleep(5)
        r.mark("stt")
        val total = r.totalMs()
        assertTrue("total >= 13 but was $total", total >= 12)
        val j = r.toJson()
        assertEquals(3, j.getJSONObject("events").length())
        assertTrue(j.getInt("total_ms") >= 12)
    }
}
