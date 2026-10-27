package com.rnbggeo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONObject

/** Geofencing: phát hiện vào/ra khu vực (dùng Android Geofencing API, miễn phí, tối đa 100 vùng/app). */
class GeofenceReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.rnbggeo.GEOFENCE_TRANSITION"
        const val ENTER = 1
        const val EXIT = 2
        const val DWELL = 4

        fun pendingIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, GeofenceReceiver::class.java).setAction(ACTION)
            return PendingIntent.getBroadcast(
                ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // app có thể vừa được hệ thống dựng lại chỉ để nhận broadcast này
        Diag.attach(context)
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val action = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> EXIT
            Geofence.GEOFENCE_TRANSITION_DWELL -> DWELL
            else -> 0
        }
        if (action == 0) return

        val triggering = event.triggeringGeofences ?: return
        val bestLoc = event.triggeringLocation
        val actionName = when (action) {
            ENTER -> "ENTER"
            EXIT -> "EXIT"
            else -> "DWELL"
        }

        for (gf in triggering) {
            // Đa giác không đăng ký với GS API; nếu bản cũ còn sót thì bỏ qua
            // để không bắn ENTER giả khi mới vào vòng tròn bao.
            val fence = findFence(context, gf.requestId)
            if (GeoMath.isPolygon(fence)) continue

            // Chế độ "không ghi điểm trong vùng": cập nhật tập vùng đang ở trong.
            // EXIT còn là tín hiệu ĐÁNH THỨC: GPS có thể đang tạm nghỉ, phải bật lại
            // để ghi chuyến đi tiếp. Đây là cơ chế dự phòng thứ hai sau significant-motion.
            if (action == ENTER) {
                Prefs.addInsideFence(context, gf.requestId)
            } else if (action == EXIT) {
                Prefs.removeInsideFence(context, gf.requestId)
                if (Prefs.desired(context)) TrackingService.start(context)
            }

            val o = JSONObject()
            o.put("identifier", gf.requestId)
            o.put("action", action)
            o.put("actionName", actionName)
            o.put("timestamp", System.currentTimeMillis())
            fence?.optString("name")?.takeIf { it.isNotEmpty() }?.let { o.put("name", it) }
            fence?.optJSONObject("extras")?.let { o.put("extras", it) }
            if (bestLoc != null) o.put("location", locationJson(bestLoc))
            // Gắn device_id/name/platform: thiếu 3 khoá này thì server không gán sự kiện cho
            // máy nào (rơi vào nhóm "unknown") và viewer không lọc được theo thiết bị.
            // Đo 23/10: 56/56 sự kiện geofence trên server đều thiếu device_id.
            Util.tagDevice(o, context)
            Diag.event(
                context, "geofence",
                "${gf.requestId} $actionName" +
                    (bestLoc?.let { String.format(java.util.Locale.US, " @ %.5f,%.5f", it.latitude, it.longitude) } ?: ""),
                o
            )
            // emit lên JS nếu app đang mở
            Events.emit(context, Events.GEOFENCE, o.toString())
            // gửi lên server (best-effort, offline thì bỏ qua event này)
            HttpUploader.postGeofenceEvent(context, o)
            // thông báo hệ thống — chạy được cả khi app đã bị kill
            GeofenceNotifier.notify(
                context,
                fence ?: JSONObject().put("identifier", gf.requestId),
                action,
                bestLoc?.latitude,
                bestLoc?.longitude
            )
        }
    }

    private fun locationJson(loc: android.location.Location): JSONObject {
        val c = JSONObject()
        c.put("latitude", loc.latitude)
        c.put("longitude", loc.longitude)
        c.put("speed", loc.speed.toDouble())
        c.put("accuracy", loc.accuracy.toDouble())
        return c
    }

    private fun findFence(ctx: Context, id: String): JSONObject? {
        val arr = Prefs.geofences(ctx)
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            if (j.optString("identifier") == id) return j
        }
        return null
    }
}

object Geofences {

    /**
     * Android chỉ cho giám sát ~100 vùng mỗi app. Chừa biên an toàn.
     */
    private const val MAX_ACTIVE = 90

    /** Chống quét lại danh sách geofence ở mỗi mẫu GPS khi chưa đi đâu xa. */
    private var lastSyncLat = Double.NaN
    private var lastSyncLng = Double.NaN
    private var lastSyncAt = 0L

    /**
     * Đăng ký geofence với hệ điều hành.
     *
     * Không đăng ký thẳng tất cả: nếu đã biết vị trí hiện tại thì đi qua [syncNearby] để
     * chỉ nạp những vùng ở gần — nhờ vậy lưu được bao nhiêu vùng cũng được, vượt giới hạn 100.
     */
    fun register(ctx: Context, client: GeofencingClient, list: List<JSONObject>) {
        // Đa giác do Polygons chấm điểm cục bộ — chỉ đăng ký hình tròn với OS.
        val circles = list.filter { !GeoMath.isPolygon(it) }
        if (circles.isEmpty()) return
        val last = Prefs.lastLocation(ctx)?.optJSONObject("coords")
        if (last != null) {
            syncNearby(ctx, last.optDouble("latitude"), last.optDouble("longitude"), force = true)
            return
        }
        // chưa từng có vị trí nào → đăng ký thẳng, cắt bớt cho vừa giới hạn
        addToOs(ctx, client, circles.take(MAX_ACTIVE))
    }

    /**
     * Chỉ giữ đăng ký những vùng nằm trong `geofenceProximityRadius` của vị trí hiện tại.
     *
     * Đây là cách phá giới hạn 100 vùng của Android: toàn bộ geofence nằm trong Prefs,
     * còn hệ điều hành chỉ phải theo dõi vài chục vùng gần nhất.
     *
     * Gỡ theo từng id chứ KHÔNG gỡ hết rồi thêm lại — gỡ hết sẽ làm vùng đang đứng bên
     * trong bắn lại ENTER, sinh sự kiện trùng.
     */
    fun syncNearby(ctx: Context, lat: Double, lng: Double, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && !lastSyncLat.isNaN() &&
            now - lastSyncAt < 60_000L &&
            Util.haversine(lastSyncLat, lastSyncLng, lat, lng) < 50.0
        ) return
        lastSyncLat = lat
        lastSyncLng = lng
        lastSyncAt = now

        val all = Prefs.geofences(ctx)
        if (all.length() == 0) return
        val client = LocationServices.getGeofencingClient(ctx)
        val proximity = Prefs.config(ctx).optDouble("geofenceProximityRadius", 1000.0)

        val cands = ArrayList<Pair<JSONObject, Double>>(all.length())
        for (i in 0 until all.length()) {
            val j = all.optJSONObject(i) ?: continue
            if (j.optString("identifier").isEmpty()) continue
            // đa giác không có bán kính cố định và không đăng ký với OS
            if (GeoMath.isPolygon(j)) continue
            val d = Util.haversine(lat, lng, j.optDouble("latitude"), j.optDouble("longitude"))
            // tính cả bán kính của vùng: tâm ở ngoài nhưng mép vùng đã chạm tới thì vẫn nạp
            if (d <= proximity + j.optDouble("radius", 200.0)) cands.add(j to d)
        }
        cands.sortBy { it.second }
        val want = cands.take(MAX_ACTIVE).map { it.first }
        val wantIds = want.map { it.optString("identifier") }.toSet()
        val had = Prefs.activeGeofences(ctx)

        val toRemove = had - wantIds
        val toAdd = want.filter { it.optString("identifier") !in had }

        if (toRemove.isNotEmpty()) {
            runCatching {
                client.removeGeofences(toRemove.toList()).addOnSuccessListener {
                    Diag.log("geofence: gỡ ${toRemove.size} vùng đã ra xa")
                }
            }
        }
        if (toAdd.isNotEmpty()) addToOs(ctx, client, toAdd)

        if (toRemove.isNotEmpty() || toAdd.isNotEmpty()) {
            Prefs.setActiveGeofences(ctx, wantIds)
            val totalCircles = (0 until all.length()).count {
                !GeoMath.isPolygon(all.optJSONObject(it))
            }
            Diag.log(
                "geofence: giám sát ${wantIds.size}/$totalCircles vùng tròn " +
                    "(bán kính ${proximity.toInt()}m)"
            )
            emitGeofencesChange(ctx)
        }
    }

    /**
     * Tập geofence đang CHỨA điểm (lat,lng). Tính trực tiếp từ định nghĩa vùng, không
     * phụ thuộc vào việc hệ điều hành có bắn ENTER/EXIT hay không.
     *
     * Đây là lớp dự phòng cho chế độ "không ghi điểm trong vùng": nếu geofence của OS bị
     * lỡ EXIT, mẫu GPS đầu tiên ở ngoài vẫn tự kết luận được là đã ra ngoài.
     */
    fun insideIds(ctx: Context, lat: Double, lng: Double): Set<String> {
        val out = HashSet<String>()
        val arr = Prefs.geofences(ctx)
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val id = g.optString("identifier")
            if (id.isEmpty()) continue
            if (GeoMath.isPolygon(g)) {
                val poly = GeoMath.vertices(g) ?: continue
                if (GeoMath.contains(lat, lng, poly)) out.add(id)
            } else {
                val r = g.optDouble("radius", 0.0)
                if (r > 0.0 && Util.haversine(
                        lat, lng,
                        g.optDouble("latitude"), g.optDouble("longitude")
                    ) <= r
                ) out.add(id)
            }
        }
        return out
    }

    private fun toFence(j: JSONObject): Geofence {
        val types = (if (j.optBoolean("notifyOnEntry", true)) Geofence.GEOFENCE_TRANSITION_ENTER else 0) or
            (if (j.optBoolean("notifyOnExit", true)) Geofence.GEOFENCE_TRANSITION_EXIT else 0) or
            (if (j.optBoolean("notifyOnDwell", false)) Geofence.GEOFENCE_TRANSITION_DWELL else 0)
        return Geofence.Builder()
            .setRequestId(j.optString("identifier"))
            .setCircularRegion(
                j.optDouble("latitude"),
                j.optDouble("longitude"),
                j.optDouble("radius", 200.0).toFloat()
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(types)
            .setLoiteringDelay(if (j.has("loiteringDelay")) j.optInt("loiteringDelay") * 1000 else 30000)
            .build()
    }

    private fun addToOs(ctx: Context, client: GeofencingClient, list: List<JSONObject>) {
        if (list.isEmpty()) return
        val fences = list.map { toFence(it) }
        val req = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences)
            .build()
        try {
            client.addGeofences(req, GeofenceReceiver.pendingIntent(ctx))
                .addOnSuccessListener {
                    Diag.log("geofences registered: ${fences.size}")
                    val ids = Prefs.activeGeofences(ctx) + fences.map { it.requestId }
                    Prefs.setActiveGeofences(ctx, ids)
                    emitGeofencesChange(ctx)
                }
                .addOnFailureListener { e ->
                    Diag.log("geofence register FAILED: ${e.message}")
                }
        } catch (e: SecurityException) {
            Diag.log("geofence SecurityException: ${e.message}")
        } catch (e: Exception) {
            Diag.log("geofence exception: ${e.message}")
        }
    }

    fun removeAll(ctx: Context, client: GeofencingClient) {
        try { client.removeGeofences(GeofenceReceiver.pendingIntent(ctx)) } catch (e: Exception) {}
        Prefs.setActiveGeofences(ctx, emptySet())
        emitGeofencesChange(ctx)
    }

    /** Bắn event onGeofencesChange lên JS với danh sách geofence hiện tại. */
    private fun emitGeofencesChange(ctx: Context) {
        val arr = Prefs.geofences(ctx)
        val o = JSONObject()
        o.put("count", arr.length())
        o.put("active", Prefs.activeGeofences(ctx).size)
        o.put("geofences", arr)
        o.put("timestamp", System.currentTimeMillis())
        Events.emit(ctx, Events.GEOFENCES_CHANGE, o.toString())
    }
}
