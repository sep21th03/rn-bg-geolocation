package com.rnbggeo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val NAME = "rn_bg_geo_prefs"
    const val KEY_CONFIG = "config"
    const val KEY_ENABLED = "enabled"

    /**
     * Người dùng CÓ muốn theo dõi hay không (do start()/stop() đặt).
     * Khác với [KEY_ENABLED] = service đang chạy thật: khi đứng yên, motion detection
     * tắt GPS nhưng desired vẫn true → cử động là tự bật lại.
     */
    const val KEY_DESIRED = "desired"
    const val KEY_IS_MOVING = "is_moving"

    /**
     * Service đang chạy nhưng ĐÃ TẮT GPS vì đứng yên (tiết kiệm pin).
     * Service vẫn sống để không mất đăng ký Activity Recognition — nếu tắt hẳn service,
     * tiến trình bị hệ thống thu hồi và cảm biến chuyển động ngừng báo về.
     */
    const val KEY_STATIONARY = "stationary"
    const val KEY_ACTIVITY = "activity"
    const val KEY_LAST_LOCATION = "last_location"

    /**
     * Thời điểm GPS (Location.time, epoch ms) của mẫu đã ghi gần nhất.
     *
     * Vì sao phải lưu riêng: `last_location.timestamp` là giờ ĐỒNG HỒ lúc ghi, còn bộ lọc
     * giữ mẫu lại một nhịp (look-ahead) nên giờ ghi luôn TRỄ hơn giờ fix ~1 nhịp GPS.
     * Trừ hai loại thời gian đó cho nhau ra số ÂM → odometer tưởng "đứt quãng" và không
     * cộng gì (đã xảy ra: 7723,63 m đứng im suốt chuyến 20/10). So giờ-fix với giờ-fix
     * mới đúng.
     */
    const val KEY_LAST_FIX_TIME = "last_fix_time"
    const val KEY_ODOMETER = "odometer"

    /** Lịch chạy đang bật hay tạm dừng (startSchedule/stopSchedule). */
    const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    const val KEY_GEOFENCES = "geofences"

    /**
     * Tập geofence ĐANG được đăng ký với hệ điều hành (chỉ những vùng gần vị trí hiện tại).
     * Cần nhớ để khi di chuyển chỉ gỡ vùng đã ra xa và thêm vùng mới — gỡ hết rồi thêm lại
     * sẽ làm bắn lại ENTER cho những vùng đang đứng bên trong.
     */
    const val KEY_GEOFENCES_ACTIVE = "geofences_active"

    /**
     * Các geofence mà thiết bị đang ở TRONG, dạng tập identifier.
     * Dùng để biết có đang ở "chế độ trong vùng" hay không. Một tập chứ không phải cờ
     * boolean vì các vùng có thể chồng nhau: ra khỏi vùng A nhưng vẫn còn trong vùng B.
     */
    const val KEY_INSIDE_FENCES = "inside_fences"

    /**
     * Trạng thái trong/ngoài của geofence ĐA GIÁC, dạng:
     * `{ "<identifier>": { "inside": true, "since": 169..., "dwellFired": false } }`
     * Cần lưu để app restart không bắn lại ENTER (hoặc bỏ sót EXIT).
     */
    const val KEY_GEOFENCE_STATE = "geofence_state"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // Cache JSON đã parse. Mỗi mẫu GPS gọi config() vài lần; parse lại JSONObject
    // mỗi lần là CPU vô ích. Các đối tượng trả về chỉ được ĐỌC — nơi nào cần sửa
    // phải copy trước rồi saveConfig().
    @Volatile private var cfgRaw: String? = null
    @Volatile private var cfgObj: JSONObject? = null

    @Volatile private var geoRaw: String? = null
    @Volatile private var geoArr: JSONArray? = null

    /** Tăng mỗi lần saveConfig() — dùng để biết bộ lọc có cần dựng lại không. */
    @Volatile
    var configVersion: Long = 0L
        private set

    fun config(ctx: Context): JSONObject {
        val s = prefs(ctx).getString(KEY_CONFIG, null)
        val cached = cfgObj
        if (cached != null && s == cfgRaw) return cached
        val parsed = try { if (s.isNullOrEmpty()) JSONObject() else JSONObject(s) }
        catch (e: Exception) { JSONObject() }
        cfgRaw = s
        cfgObj = parsed
        return parsed
    }

    fun saveConfig(ctx: Context, config: JSONObject) {
        prefs(ctx).edit().putString(KEY_CONFIG, config.toString()).apply()
        cfgRaw = null
        cfgObj = null
        configVersion++
    }

    /** Xoá cấu hình + trạng thái "đang trong vùng" (dùng cho reset()). */
    fun clearConfig(ctx: Context) {
        prefs(ctx).edit().remove(KEY_CONFIG).remove(KEY_INSIDE_FENCES).apply()
        cfgRaw = null
        cfgObj = null
        configVersion++
    }

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    /** Ý muốn của người dùng — xem chú thích ở [KEY_DESIRED]. */
    fun desired(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DESIRED, false)
    fun setDesired(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_DESIRED, v).apply()

    fun isMoving(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_IS_MOVING, false)
    fun setMoving(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_IS_MOVING, v).apply()

    fun stationary(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_STATIONARY, false)
    fun setStationary(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_STATIONARY, v).apply()

    fun activity(ctx: Context): String = prefs(ctx).getString(KEY_ACTIVITY, "unknown") ?: "unknown"
    fun setActivity(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_ACTIVITY, v).apply()

    fun lastLocation(ctx: Context): JSONObject? {
        val s = prefs(ctx).getString(KEY_LAST_LOCATION, null) ?: return null
        return try { JSONObject(s) } catch (e: Exception) { null }
    }

    fun saveLastLocation(ctx: Context, json: JSONObject) =
        prefs(ctx).edit().putString(KEY_LAST_LOCATION, json.toString()).apply()

    /** Giờ-fix (Location.time) của mẫu đã ghi gần nhất — dùng cho odometer. */
    fun lastFixTime(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_FIX_TIME, 0L)
    fun setLastFixTime(ctx: Context, ms: Long) =
        prefs(ctx).edit().putLong(KEY_LAST_FIX_TIME, ms).apply()

    fun odometer(ctx: Context): Double = prefs(ctx).getFloat(KEY_ODOMETER, 0f).toDouble()
    fun addOdometer(ctx: Context, meters: Double) =
        prefs(ctx).edit().putFloat(KEY_ODOMETER, (odometer(ctx) + meters).toFloat()).apply()

    fun resetOdometer(ctx: Context) = prefs(ctx).edit().putFloat(KEY_ODOMETER, 0f).apply()

    /** Gán giá trị odometer (giống transistorsoft setOdometer). */
    fun setOdometer(ctx: Context, meters: Double) =
        prefs(ctx).edit().putFloat(KEY_ODOMETER, meters.toFloat()).apply()

    fun scheduleEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SCHEDULE_ENABLED, true)

    fun setScheduleEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SCHEDULE_ENABLED, v).apply()

    fun geofences(ctx: Context): JSONArray {
        val s = prefs(ctx).getString(KEY_GEOFENCES, null)
        val cached = geoArr
        if (cached != null && s == geoRaw) return cached
        val parsed = try { if (s.isNullOrEmpty()) JSONArray() else JSONArray(s) }
        catch (e: Exception) { JSONArray() }
        geoRaw = s
        geoArr = parsed
        return parsed
    }

    fun saveGeofences(ctx: Context, arr: JSONArray) {
        prefs(ctx).edit().putString(KEY_GEOFENCES, arr.toString()).apply()
        geoRaw = null
        geoArr = null
    }

    fun activeGeofences(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_GEOFENCES_ACTIVE, emptySet()) ?: emptySet()

    fun setActiveGeofences(ctx: Context, ids: Set<String>) =
        prefs(ctx).edit().putStringSet(KEY_GEOFENCES_ACTIVE, ids).apply()

    fun insideFences(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_INSIDE_FENCES, emptySet()) ?: emptySet()

    fun setInsideFences(ctx: Context, ids: Set<String>) =
        prefs(ctx).edit().putStringSet(KEY_INSIDE_FENCES, ids).apply()

    fun addInsideFence(ctx: Context, id: String) =
        setInsideFences(ctx, insideFences(ctx) + id)

    fun removeInsideFence(ctx: Context, id: String) =
        setInsideFences(ctx, insideFences(ctx) - id)

    fun geofenceState(ctx: Context): JSONObject {
        val s = prefs(ctx).getString(KEY_GEOFENCE_STATE, null) ?: return JSONObject()
        return try { JSONObject(s) } catch (e: Exception) { JSONObject() }
    }

    fun saveGeofenceState(ctx: Context, state: JSONObject) =
        prefs(ctx).edit().putString(KEY_GEOFENCE_STATE, state.toString()).apply()
}
