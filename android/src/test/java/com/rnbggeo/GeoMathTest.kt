package com.rnbggeo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test toán học geofence đa giác. Không dùng Android/JSON nên chạy thuần JVM:
 *   ./gradlew :rn-bg-geolocation:testDebugUnitTest
 */
class GeoMathTest {

    private val cLat = 21.0
    private val cLng = 105.0

    /**
     * Hình vuông thật (~222 m mỗi cạnh) quanh (21, 105).
     * 1° kinh tại vĩ độ 21 chỉ ~103.9 km, nên phải chia cho cos(vĩ độ)
     * để cạnh ngang dài bằng cạnh dọc.
     */
    private fun square(halfDeg: Double = 0.001): List<GeoMath.Pt> {
        val halfLng = halfDeg / Math.cos(Math.toRadians(cLat))
        return listOf(
            GeoMath.Pt(cLat + halfDeg, cLng + halfLng),
            GeoMath.Pt(cLat + halfDeg, cLng - halfLng),
            GeoMath.Pt(cLat - halfDeg, cLng - halfLng),
            GeoMath.Pt(cLat - halfDeg, cLng + halfLng)
        )
    }

    /** Hình chữ L (lõm) — kiểm tra ray casting không "lấp" phần khuyết. */
    private fun lShape(): List<GeoMath.Pt> = listOf(
        GeoMath.Pt(cLat, cLng),
        GeoMath.Pt(cLat + 0.002, cLng),
        GeoMath.Pt(cLat + 0.002, cLng + 0.001),
        GeoMath.Pt(cLat + 0.001, cLng + 0.001),
        GeoMath.Pt(cLat + 0.001, cLng + 0.002),
        GeoMath.Pt(cLat, cLng + 0.002)
    )

    @Test
    fun `điểm giữa hình vuông nằm trong`() {
        assertTrue(GeoMath.contains(cLat, cLng, square()))
    }

    @Test
    fun `điểm ngoài hình vuông nằm ngoài`() {
        assertFalse(GeoMath.contains(cLat + 0.002, cLng, square()))
        assertFalse(GeoMath.contains(cLat, cLng - 0.002, square()))
    }

    @Test
    fun `đa giác lõm - điểm trong phần khuyết phải là ngoài`() {
        assertTrue(GeoMath.contains(cLat + 0.0005, cLng + 0.0015, lShape()))
        assertFalse(GeoMath.contains(cLat + 0.0015, cLng + 0.0015, lShape()))
    }

    @Test
    fun `khoảng cách tới biên ở tâm xấp xỉ nửa cạnh`() {
        val d = GeoMath.edgeDistance(cLat, cLng, square())
        // 0.001 độ vĩ ≈ 111.32 m
        assertEquals(111.32, d, 2.0)
    }

    @Test
    fun `điểm cách biên ít hơn buffer thì khoảng cách nhỏ`() {
        val d = GeoMath.edgeDistance(cLat + 0.0009, cLng, square())
        assertEquals(11.13, d, 2.0)
    }

    @Test
    fun `vòng tròn bao phủ mọi đỉnh`() {
        val poly = square()
        val (ccLat, ccLng, r) = GeoMath.enclosingCircle(poly)
        assertEquals(cLat, ccLat, 1e-9)
        assertEquals(cLng, ccLng, 1e-9)
        poly.forEach {
            assertTrue(GeoMath.haversine(ccLat, ccLng, it.lat, it.lng) <= r + 0.001)
        }
        assertEquals(157.4, r, 2.0)
    }

    @Test
    fun `diện tích hình vuông 222m xấp xỉ 49 nghin m2`() {
        assertEquals(49_568.0, GeoMath.areaMeters2(square()), 700.0)
    }

    @Test
    fun `đa giác suy biến có diện tích 0`() {
        val line = listOf(
            GeoMath.Pt(cLat, cLng),
            GeoMath.Pt(cLat + 0.001, cLng),
            GeoMath.Pt(cLat + 0.002, cLng)
        )
        assertEquals(0.0, GeoMath.areaMeters2(line), 0.5)
    }
}
