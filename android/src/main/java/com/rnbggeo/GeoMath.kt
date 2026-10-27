package com.rnbggeo

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Toán học thuần cho geofence đa giác (polygon).
 *
 * Tách khỏi Android để có thể test trên JVM (`GeoMathTest`) — không import
 * `android.*` ở đây, nhờ vậy unit test chạy được mà không cần máy ảo.
 *
 * Quy ước toạ độ: `Pt(lat, lng)`. Phép "point-in-polygon" dùng ray casting trên
 * mặt phẳng lat/lng — sai số không đáng kể với vùng có kích thước dưới vài km,
 * đúng phạm vi sử dụng của geofence.
 */
object GeoMath {

    data class Pt(val lat: Double, val lng: Double)

    /**
     * Đọc `vertices` theo 2 dạng đều chấp nhận:
     *  - `[[lat, lng], ...]`  (giống transistorsoft)
     *  - `[{latitude, longitude}, ...]`
     *
     * Trả `null` nếu ít hơn 3 đỉnh hợp lệ.
     */
    fun vertices(g: JSONObject?): List<Pt>? {
        val a = g?.optJSONArray("vertices") ?: return null
        if (a.length() < 3) return null
        val out = ArrayList<Pt>(a.length())
        for (i in 0 until a.length()) {
            val v = a.opt(i)
            val p = when (v) {
                is JSONArray -> if (v.length() >= 2) Pt(v.optDouble(0), v.optDouble(1)) else null
                is JSONObject -> Pt(v.optDouble("latitude"), v.optDouble("longitude"))
                else -> null
            }
            if (p != null && p.lat in -90.0..90.0 && p.lng in -180.0..180.0) out.add(p)
        }
        return if (out.size >= 3) out else null
    }

    fun isPolygon(g: JSONObject?): Boolean = vertices(g) != null

    /**
     * Ray casting: bắn tia ngang sang phải, đếm số lần cắt biên.
     * Điểm nằm đúng trên biên được coi là "ngoài" — đây là lý do cần
     * [edgeDistance] để chống nhấp nháy khi GPS lệch quanh mép.
     */
    fun contains(lat: Double, lng: Double, poly: List<Pt>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]
            val pj = poly[j]
            if ((pi.lat > lat) != (pj.lat > lat)) {
                val x = (pj.lng - pi.lng) * (lat - pi.lat) / (pj.lat - pi.lat) + pi.lng
                if (lng < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Khoảng cách (m) từ điểm tới cạnh gần nhất của đa giác. */
    fun edgeDistance(lat: Double, lng: Double, poly: List<Pt>): Double {
        val mPerLat = 111_320.0
        val mPerLng = mPerLat * cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        var best = Double.MAX_VALUE
        var j = poly.size - 1
        for (i in poly.indices) {
            // đưa 3 điểm về hệ mét quanh điểm cần đo để tính khoảng cách điểm–đoạn
            val ax = (poly[j].lng - lng) * mPerLng
            val ay = (poly[j].lat - lat) * mPerLat
            val bx = (poly[i].lng - lng) * mPerLng
            val by = (poly[i].lat - lat) * mPerLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (-(ax * dx) - (ay * dy)) / len2
            val tt = t.coerceIn(0.0, 1.0)
            val cx = ax + tt * dx
            val cy = ay + tt * dy
            val d = sqrt(cx * cx + cy * cy)
            if (d < best) best = d
            j = i
        }
        return if (best == Double.MAX_VALUE) 0.0 else best
    }

    /**
     * Vòng tròn bao đa giác (xấp xỉ, không phải minimum enclosing circle).
     * Chỉ dùng để lưu `latitude/longitude/radius` cho UI vẽ và để log —
     * việc chấm điểm luôn làm trên đa giác thật.
     */
    fun enclosingCircle(poly: List<Pt>): Triple<Double, Double, Double> {
        var cLat = 0.0
        var cLng = 0.0
        poly.forEach { cLat += it.lat; cLng += it.lng }
        cLat /= poly.size
        cLng /= poly.size
        var r = 0.0
        poly.forEach { r = max(r, haversine(cLat, cLng, it.lat, it.lng)) }
        return Triple(cLat, cLng, r)
    }

    /** Khoảng cách mặt cầu (m) — bản sao độc lập của `Util.haversine` để test được. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return r * 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
    }

    /** Diện tích đa giác (m²) — dùng để từ chối đa giác quá nhỏ / tự cắt. */
    fun areaMeters2(poly: List<Pt>): Double {
        if (poly.size < 3) return 0.0
        val mPerLat = 111_320.0
        val mPerLng = mPerLat * cos(Math.toRadians(poly[0].lat)).coerceAtLeast(1e-6)
        var sum = 0.0
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = (poly[i].lng - poly[0].lng) * mPerLng
            val yi = (poly[i].lat - poly[0].lat) * mPerLat
            val xj = (poly[j].lng - poly[0].lng) * mPerLng
            val yj = (poly[j].lat - poly[0].lat) * mPerLat
            sum += xj * yi - xi * yj
            j = i
        }
        return abs(sum) / 2.0
    }

    /** Bán kính vòng tròn bao, làm tròn lên mét. */
    fun enclosingRadius(poly: List<Pt>): Double = round(enclosingCircle(poly).third)

    /** Kẹp giá trị — tiện cho chống nhấp nháy. */
    fun clamp(v: Double, lo: Double, hi: Double): Double = min(max(v, lo), hi)
}
