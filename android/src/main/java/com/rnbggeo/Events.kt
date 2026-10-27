package com.rnbggeo

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule

object Events {
    const val LOCATION = "rnbggeo:location"
    const val MOTION = "rnbggeo:motionchange"
    const val GEOFENCE = "rnbggeo:geofence"
    const val HTTP = "rnbggeo:http"
    const val ENABLED = "rnbggeo:enabledchange"
    const val ACTIVITY = "rnbggeo:activitychange"
    const val CONNECTIVITY = "rnbggeo:connectivity"
    const val GEOFENCES_CHANGE = "rnbggeo:geofenceschange"
    const val POWERSAVE = "rnbggeo:powersavechange"

    /** Trạng thái GPS/quyền (onProviderChange / onAuthorization). */
    const val PROVIDER = "rnbggeo:providerchange"
    const val AUTHORIZATION = "rnbggeo:authorization"

    /** Nhịp tim có vị trí + pin (onHeartbeat). */
    const val HEARTBEAT = "rnbggeo:heartbeat"

    /** Lịch chạy bật/tắt (onSchedule). */
    const val SCHEDULE = "rnbggeo:schedule"

    /** Bắn khi một mẫu GPS bị bộ lọc loại — để client biết vì sao mất điểm. */
    const val LOCATION_FILTER = "rnbggeo:locationfilter"

    /** React context còn sống hay không — do BgGeoModule gán lúc khởi tạo, xoá khi module huỷ. */
    @Volatile
    var reactContext: ReactApplicationContext? = null

    /** Dùng lại Handler thay vì tạo mới cho mỗi event. */
    private val main = Handler(Looper.getMainLooper())

    /**
     * Bắn event lên JS. Nếu app đã bị kill (không còn React context) thì bỏ qua —
     * dữ liệu vẫn được ghi xuống SQLite và gửi lên server ở tầng native.
     */
    fun emit(ctx: Context?, name: String, json: String) {
        val reactCtx = reactContext ?: return
        main.post {
            try {
                if (!reactCtx.hasActiveReactInstance()) return@post
                val map = Arguments.createMap()
                map.putString("payload", json)
                reactCtx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit(name, map)
            } catch (e: Exception) {
                // JS đã tắt — bỏ qua
            }
        }
    }
}
