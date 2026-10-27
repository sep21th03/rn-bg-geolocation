package com.rnbggeo

import android.content.Context
import android.location.Location
import android.os.Build
import android.provider.Settings
import java.util.UUID

object Util {
    fun uuid(): String = UUID.randomUUID().toString()

    /**
     * Mã thiết bị ổn định để server/viewer lọc theo máy.
     * Android: Settings.Secure.ANDROID_ID (đổi khi factory reset — chấp nhận được).
     */
    object DeviceId {
        @Volatile private var cached: String? = null

        fun id(ctx: Context): String {
            cached?.let { return it }
            val v = try {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                null
            }
            val s = if (v.isNullOrBlank()) "android-unknown" else v
            cached = s
            return s
        }

        fun name(): String = Build.MODEL ?: "android"
    }

    /** Gắn device_id / device_name / device_platform vào bản ghi (server dùng để lọc). */
    fun tagDevice(o: org.json.JSONObject, ctx: Context): org.json.JSONObject {
        try {
            o.put("device_id", DeviceId.id(ctx))
            o.put("device_name", DeviceId.name())
            o.put("device_platform", "android")
        } catch (e: Exception) {
        }
        return o
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun toJson(location: Location, activityType: String?, activityConfidence: Int, isMoving: Boolean, event: String, batteryLevel: Float, isCharging: Boolean, odometer: Double, mock: Boolean): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("uuid", uuid())
        o.put("timestamp", System.currentTimeMillis())
        o.put("event", event)
        o.put("is_moving", isMoving)
        val coords = org.json.JSONObject()
        coords.put("latitude", location.latitude)
        coords.put("longitude", location.longitude)
        coords.put("speed", location.speed.toDouble())
        coords.put("heading", location.bearing.toDouble())
        coords.put("accuracy", location.accuracy.toDouble())
        if (location.hasAltitude()) coords.put("altitude", location.altitude) else coords.put("altitude", org.json.JSONObject.NULL)
        o.put("coords", coords)
        val act = org.json.JSONObject()
        act.put("type", activityType ?: "unknown")
        act.put("confidence", activityConfidence)
        o.put("activity", act)
        val bat = org.json.JSONObject()
        bat.put("level", batteryLevel.toDouble())
        bat.put("is_charging", isCharging)
        o.put("battery", bat)
        o.put("odometer", odometer)
        o.put("mock", mock)
        return o
    }
}
