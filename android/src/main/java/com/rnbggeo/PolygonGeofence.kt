package com.rnbggeo

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Geofence ĐA GIÁC (polygon) — vùng có hình bất kỳ.
 *
 * ## Vì sao phải tự làm
 * Android Geofencing API chỉ hỗ trợ hình tròn. Thư viện gốc (transistorsoft)
 * cũng phải tự "hit-test" đa giác: dựng vòng tròn bao đăng ký với hệ điều
 * hành để đánh thức, rồi khi vào gần mới chấm điểm đa giác thật.
 *
 * Ở đây đơn giản hơn nhưng cùng nguyên lý:
 *  - Đa giác KHÔNG đăng ký với Geofencing API (tránh bắn ENTER giả khi mới
 *    vào vòng tròn bao).
 *  - Mỗi mẫu GPS ĐÃ QUA BỘ LỌC được chấm điểm bằng ray casting
 *    ([GeoMath.contains]) — rẻ, vài chục đa giác vẫn không đáng kể.
 *  - Trạng thái trong/ngoài lưu vào SharedPreferences để sống qua app restart.
 *  - Chống nhấp nháy ở mép: ra khỏi vùng chỉ tính khi đã cách biên hơn
 *    `max(độ chính xác GPS, 10m)` (có kẹp theo kích thước vùng).
 *
 * ## Cấu hình mỗi vùng
 * ```
 * {
 *   identifier: "CongTy",
 *   vertices: [[21.56, 105.85], [21.57, 105.85], [21.57, 105.86]], // [lat, lng]
 *   notifyOnEntry: true,
 *   notifyOnExit: true,
 *   notifyOnDwell: false,
 *   loiteringDelay: 30,   // GIÂY — giữ đồng bộ với phần còn lại của lib
 *   extras: { route_id: 123 }
 * }
 * ```
 * `latitude/longitude/radius` tự tính (vòng tròn bao) để UI cũ vẫn vẽ được.
 */
object Polygons {

    private const val DEFAULT_LOITERING_MS = 30_000L
    private const val TAG = "geofence"

    private val handler = Handler(Looper.getMainLooper())
    private val dwellTimers = HashMap<String, Runnable>()

    /** Số giây "nán lại" → ms. Giữ đơn vị GIÂY như geofence tròn của lib này. */
    private fun loiteringMs(g: JSONObject): Long {
        if (!g.has("loiteringDelay")) return DEFAULT_LOITERING_MS
        val v = g.optDouble("loiteringDelay", 0.0)
        return max(0L, (v * 1000).toLong())
    }

    /**
     * Kiểm tra + điền thông tin dẫn xuất cho một geofence đa giác.
     * Trả false nếu `vertices` không hợp lệ.
     */
    fun normalize(g: JSONObject): Boolean {
        val poly = GeoMath.vertices(g) ?: return false
        if (GeoMath.areaMeters2(poly) < 1.0) return false
        val (cLat, cLng, r) = GeoMath.enclosingCircle(poly)
        if (!g.has("latitude") || !g.has("longitude")) {
            g.put("latitude", cLat)
            g.put("longitude", cLng)
        }
        if (!g.has("radius") || g.optDouble("radius", 0.0) <= 0.0) {
            g.put("radius", kotlin.math.ceil(r))
        }
        g.put("polygon", true)
        return true
    }

    /**
     * Chấm điểm mọi geofence đa giác với một mẫu GPS đã được nhận.
     * Gọi từ [TrackingService.commit] trên main thread.
     */
    fun evaluate(ctx: Context, lat: Double, lng: Double, ts: Long, locationJson: JSONObject?) {
        try {
            val arr = Prefs.geofences(ctx)
            if (arr.length() == 0) return
            val state = Prefs.geofenceState(ctx)
            var changed = false

            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val poly = GeoMath.vertices(g) ?: continue
                val id = g.optString("identifier")
                if (id.isEmpty()) continue

                val st = state.optJSONObject(id) ?: JSONObject()
                val wasInside = st.optBoolean("inside", false)
                val inside = GeoMath.contains(lat, lng, poly)

                // Vùng càng nhỏ thì buffer càng phải nhỏ, nếu không sẽ không bao giờ thoát.
                val acc = locationJson?.optJSONObject("coords")?.optDouble("accuracy", 0.0) ?: 0.0
                val r = GeoMath.enclosingRadius(poly)
                val buffer = min(GeoMath.clamp(acc, 10.0, 30.0), max(5.0, r / 3.0))
                val clearOfEdge = GeoMath.edgeDistance(lat, lng, poly) > buffer

                val d = PolygonLogic.decide(
                    inside = inside,
                    wasInside = wasInside,
                    clearOfEdge = clearOfEdge,
                    since = st.optLong("since", 0L),
                    dwellFired = st.optBoolean("dwellFired", false),
                    now = ts,
                    loiteringMs = loiteringMs(g),
                    notifyDwell = g.optBoolean("notifyOnDwell", false)
                )

                if (d.inside != wasInside ||
                    d.since != st.optLong("since", 0L) ||
                    d.dwellFired != st.optBoolean("dwellFired", false)
                ) {
                    st.put("inside", d.inside)
                    st.put("since", d.since)
                    st.put("dwellFired", d.dwellFired)
                    state.put(id, st)
                    changed = true
                }

                when (d.emit) {
                    PolygonLogic.ENTER -> {
                        Prefs.addInsideFence(ctx, id)
                        emit(ctx, g, PolygonLogic.ENTER, ts, locationJson)
                        scheduleDwell(ctx, g, ts)
                    }
                    PolygonLogic.EXIT -> {
                        Prefs.removeInsideFence(ctx, id)
                        cancelDwell(id)
                        // Ra khỏi vùng = đánh thức GPS nếu đang tạm nghỉ trong vùng.
                        if (Prefs.desired(ctx)) TrackingService.start(ctx)
                        emit(ctx, g, PolygonLogic.EXIT, ts, locationJson)
                    }
                    PolygonLogic.DWELL -> emit(ctx, g, PolygonLogic.DWELL, ts, locationJson)
                }
            }
            if (changed) Prefs.saveGeofenceState(ctx, state)
        } catch (e: Exception) {
            Diag.log("polygon evaluate lỗi: ${e.message}")
        }
    }

    /**
     * Hẹn bắn DWELL kể cả khi máy đứng yên (GPS có thể đã tắt nên không có mẫu mới).
     * Khi có mẫu mới thì [PolygonLogic.decide] cũng kiểm tra lại — hai đường, một kết quả.
     */
    private fun scheduleDwell(ctx: Context, g: JSONObject, ts: Long) {
        if (!g.optBoolean("notifyOnDwell", false)) return
        val id = g.optString("identifier")
        if (id.isEmpty()) return
        cancelDwell(id)
        val delay = max(0L, loiteringMs(g) - (System.currentTimeMillis() - ts))
        val appCtx = ctx.applicationContext
        val r = Runnable {
            dwellTimers.remove(id)
            try {
                val state = Prefs.geofenceState(appCtx)
                val st = state.optJSONObject(id) ?: return@Runnable
                if (!st.optBoolean("inside", false) || st.optBoolean("dwellFired", false)) return@Runnable
                st.put("dwellFired", true)
                state.put(id, st)
                Prefs.saveGeofenceState(appCtx, state)
                val fence = findFence(appCtx, id) ?: return@Runnable
                emit(appCtx, fence, 4, System.currentTimeMillis(), Prefs.lastLocation(appCtx))
            } catch (e: Exception) {
                Diag.log("polygon dwell lỗi: ${e.message}")
            }
        }
        dwellTimers[id] = r
        handler.postDelayed(r, delay)
    }

    private fun cancelDwell(id: String) {
        dwellTimers.remove(id)?.let { handler.removeCallbacks(it) }
    }

    fun forget(ctx: Context, identifier: String) {
        cancelDwell(identifier)
        val state = Prefs.geofenceState(ctx)
        state.remove(identifier)
        Prefs.saveGeofenceState(ctx, state)
    }

    fun forgetAll(ctx: Context) {
        dwellTimers.values.forEach { handler.removeCallbacks(it) }
        dwellTimers.clear()
        Prefs.saveGeofenceState(ctx, JSONObject())
    }

    private fun findFence(ctx: Context, id: String): JSONObject? {
        val arr = Prefs.geofences(ctx)
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            if (j.optString("identifier") == id) return j
        }
        return null
    }

    /** Bắn sự kiện theo cùng định dạng với geofence tròn. */
    private fun emit(ctx: Context, g: JSONObject, action: Int, ts: Long, locationJson: JSONObject?) {
        val notify = when (action) {
            1 -> g.optBoolean("notifyOnEntry", true)
            2 -> g.optBoolean("notifyOnExit", true)
            else -> g.optBoolean("notifyOnDwell", false)
        }
        if (!notify) return

        val id = g.optString("identifier")
        val actionName = when (action) {
            1 -> "ENTER"
            2 -> "EXIT"
            else -> "DWELL"
        }
        val o = JSONObject()
        o.put("identifier", id)
        o.put("action", action)
        o.put("actionName", actionName)
        o.put("timestamp", ts)
        o.put("polygon", true)
        g.optString("name").takeIf { it.isNotEmpty() }?.let { o.put("name", it) }
        g.optJSONObject("extras")?.let { o.put("extras", it) }
        val flat = flatLocation(locationJson ?: Prefs.lastLocation(ctx))
        flat?.let { o.put("location", it) }
        // Cùng lý do như sự kiện geofence tròn: thiếu device_id thì server gom vào "unknown".
        Util.tagDevice(o, ctx)

        Diag.event(
            ctx, TAG,
            "$id $actionName (đa giác ${GeoMath.vertices(g)?.size ?: 0} đỉnh)",
            o
        )
        Events.emit(ctx, Events.GEOFENCE, o.toString())
        HttpUploader.postGeofenceEvent(ctx, o)
        GeofenceNotifier.notify(ctx, g, action, flat?.optDouble("latitude"), flat?.optDouble("longitude"))
    }

    /**
     * Đưa location về dạng phẳng `{latitude, longitude, speed, accuracy}` —
     * giống sự kiện geofence tròn để app/server không phải xử lý 2 kiểu.
     */
    private fun flatLocation(loc: JSONObject?): JSONObject? {
        loc ?: return null
        val c = loc.optJSONObject("coords")
        val o = JSONObject()
        o.put("latitude", c?.optDouble("latitude") ?: loc.optDouble("latitude"))
        o.put("longitude", c?.optDouble("longitude") ?: loc.optDouble("longitude"))
        o.put("speed", c?.optDouble("speed", 0.0) ?: loc.optDouble("speed", 0.0))
        o.put("accuracy", c?.optDouble("accuracy", 0.0) ?: loc.optDouble("accuracy", 0.0))
        return o
    }
}
