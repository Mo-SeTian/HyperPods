package moe.chenxy.hyperpods.utils

import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey
import org.junit.Assert.*
import org.junit.Test

class DiagnosticEventsTest {
    @Test fun retainsOnlyTheLastFortyEvents() {
        val events = (1L..100L).map { DiagnosticEvents.Event(it, "connection", "connected") }
        val result = DiagnosticEvents.decode(DiagnosticEvents.encode(events))
        assertEquals(40, result.size)
        assertEquals(61L, result.first().at)
        assertEquals(100L, result.last().at)
    }

    @Test fun allRecordedKindsRoundTrip() {
        val events = listOf(
            DiagnosticEvents.create(1, "connection", "ready"),
            DiagnosticEvents.create(2, "retry", "3"),
            DiagnosticEvents.create(2, "sync", "request"),
            DiagnosticEvents.create(3, "role", "right"),
            DiagnosticEvents.create(4, "wear", "0,1"),
            DiagnosticEvents.create(5, "failure", "status_timeout"),
            DiagnosticEvents.create(6, "setting", "sent", HyperPodsPrefsKey.CONVERSATION_AWARENESS)
        ).filterNotNull()
        assertEquals(7, events.size)
        assertEquals(events, DiagnosticEvents.decode(DiagnosticEvents.encode(events)))
    }

    @Test fun unknownFieldsAndArbitraryStringsNeverReachTheReport() {
        val text = "[{\"at\":1,\"kind\":\"role\",\"detail\":\"left\",\"setting\":\"PRIVATE_VALUE\",\"mac\":\"PRIVATE_VALUE\",\"name\":\"PRIVATE_VALUE\",\"packet\":\"PRIVATE_VALUE\"}]"
        val safe = DiagnosticEvents.encode(DiagnosticEvents.decode(text))
        assertFalse(safe.contains("PRIVATE_VALUE"))
        assertFalse(safe.contains("mac"))
        assertFalse(safe.contains("name"))
        assertFalse(safe.contains("packet"))
        assertNull(DiagnosticEvents.create(1, "failure", "PRIVATE_VALUE"))
        assertNull(DiagnosticEvents.create(1, "setting", "sent", "PRIVATE_VALUE"))
        assertNull(DiagnosticEvents.create(1, "rename", "PRIVATE_VALUE"))
        assertNull(DiagnosticEvents.create(1, "wear", "0,4"))
        assertNull(DiagnosticEvents.create(0, "role", "left"))
    }

    @Test fun evenConstructedEventsMustPassTheWhitelist() {
        assertEquals("[]", DiagnosticEvents.encode(listOf(DiagnosticEvents.Event(1, "failure", "PRIVATE_VALUE"))))
    }

    @Test fun corruptAndOversizedHistoryIsIgnored() {
        assertTrue(DiagnosticEvents.decode(null).isEmpty())
        assertTrue(DiagnosticEvents.decode("not json").isEmpty())
        assertTrue(DiagnosticEvents.decode(" ".repeat(16_385)).isEmpty())
    }
}
