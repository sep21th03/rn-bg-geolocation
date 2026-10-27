package com.rnbggeo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test máy trạng thái geofence đa giác — thuần JVM. */
class PolygonLogicTest {

    private val loiter = 30_000L

    @Test
    fun `vào vùng lần đầu bắn ENTER`() {
        val d = PolygonLogic.decide(
            inside = true, wasInside = false, clearOfEdge = false,
            since = 0, dwellFired = false,
            now = 1_000, loiteringMs = loiter, notifyDwell = true
        )
        assertEquals(PolygonLogic.ENTER, d.emit)
        assertTrue(d.inside)
        assertEquals(1_000, d.since)
        assertFalse(d.dwellFired)
    }

    @Test
    fun `ở trong vùng chưa đủ lâu thì chưa DWELL`() {
        val d = PolygonLogic.decide(
            inside = true, wasInside = true, clearOfEdge = true,
            since = 1_000, dwellFired = false,
            now = 1_000 + loiter - 1, loiteringMs = loiter, notifyDwell = true
        )
        assertEquals(PolygonLogic.NONE, d.emit)
        assertFalse(d.dwellFired)
    }

    @Test
    fun `nán đủ loiteringDelay thì bắn DWELL đúng một lần`() {
        val d = PolygonLogic.decide(
            inside = true, wasInside = true, clearOfEdge = true,
            since = 1_000, dwellFired = false,
            now = 1_000 + loiter, loiteringMs = loiter, notifyDwell = true
        )
        assertEquals(PolygonLogic.DWELL, d.emit)
        assertTrue(d.dwellFired)

        val again = PolygonLogic.decide(
            inside = true, wasInside = true, clearOfEdge = true,
            since = 1_000, dwellFired = true,
            now = 1_000 + loiter * 3, loiteringMs = loiter, notifyDwell = true
        )
        assertEquals(PolygonLogic.NONE, again.emit)
    }

    @Test
    fun `không bật notifyOnDwell thì không bao giờ bắn DWELL`() {
        val d = PolygonLogic.decide(
            inside = true, wasInside = true, clearOfEdge = true,
            since = 1_000, dwellFired = false,
            now = 999_000, loiteringMs = loiter, notifyDwell = false
        )
        assertEquals(PolygonLogic.NONE, d.emit)
        assertFalse(d.dwellFired)
    }

    @Test
    fun `ra khỏi vùng nhưng còn sát biên thì giữ trạng thái trong`() {
        val d = PolygonLogic.decide(
            inside = false, wasInside = true, clearOfEdge = false,
            since = 1_000, dwellFired = false,
            now = 5_000, loiteringMs = loiter, notifyDwell = true
        )
        assertEquals(PolygonLogic.NONE, d.emit)
        assertTrue(d.inside)
    }

    @Test
    fun `ra khỏi vùng đủ xa thì bắn EXIT và reset`() {
        val d = PolygonLogic.decide(
            inside = false, wasInside = true, clearOfEdge = true,
            since = 1_000, dwellFired = true,
            now = 9_000, loiteringMs = loiter, notifyDwell = true
        )
        assertEquals(PolygonLogic.EXIT, d.emit)
        assertFalse(d.inside)
        assertEquals(9_000, d.since)
        assertFalse(d.dwellFired)
    }

    @Test
    fun `vào lại sau khi ra thì bắn ENTER`() {
        val d = PolygonLogic.decide(
            inside = true, wasInside = false, clearOfEdge = true,
            since = 9_000, dwellFired = false,
            now = 12_000, loiteringMs = loiter, notifyDwell = true
        )
        assertEquals(PolygonLogic.ENTER, d.emit)
        assertEquals(12_000, d.since)
    }

    @Test
    fun `đang ở ngoài và vẫn ngoài thì không bắn gì`() {
        val d = PolygonLogic.decide(
            inside = false, wasInside = false, clearOfEdge = true,
            since = 0, dwellFired = false,
            now = 5_000, loiteringMs = loiter, notifyDwell = true
        )
        assertEquals(PolygonLogic.NONE, d.emit)
        assertFalse(d.inside)
    }

    @Test
    fun `app restart khi đang trong vùng - since=0 được neo lại, không DWELL ngay`() {
        val d = PolygonLogic.decide(
            inside = true, wasInside = true, clearOfEdge = false,
            since = 0, dwellFired = false,
            now = 50_000, loiteringMs = loiter, notifyDwell = true
        )
        assertEquals(PolygonLogic.NONE, d.emit)
        assertEquals(50_000, d.since)
        assertFalse(d.dwellFired)
    }
}
