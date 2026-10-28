package com.rnbggeo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Các phần bổ sung để tiệm cận API của transistorsoft:
 * - [ProviderWatcher]: `onProviderChange` / `onAuthorization` / `getProviderState`
 * - [Schedule]: `schedule` dạng tuần (giống `Config.schedule`)
 * - [DeviceInfo]: `getDeviceInfo` / `getSensors`
 * - [Sound]: `playSound`
 *
 * Toàn bộ viết bằng API Android thuần, không phụ thuộc thư viện ngoài.
 */

/** Trạng thái GPS/quyền — payload của onProviderChange và onAuthorization. */
object ProviderWatcher {

    private var receiver: BroadcastReceiver? = null

    fun stateJson(ctx: Context): JSONObject {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val net = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        // status: 0 = chưa cấp/từ chối, 2 = khi đang dùng, 3 = luôn cho phép
        val status = when {
            !fine && !coarse -> 0
            !background -> 2
            else -> 3
        }
        return JSONObject().apply {
            put("enabled", gps || net)
            put("gps", gps)
            put("network", net)
            put("status", status)
            // 1 = chính xác đầy đủ, 0 = chỉ tương đối
            put("accuracyAuthorization", if (fine) 1 else 0)
            put("timestamp", System.currentTimeMillis())
        }
    }

    /** Đăng ký nghe thay đổi GPS/quyền. Gọi từ ready(). */
    fun start(ctx: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent?) {
                val s = stateJson(c)
                Events.emit(c, Events.PROVIDER, s.toString())
                Events.emit(c, Events.AUTHORIZATION, s.toString())
                Diag.event(
                    c, "provider",
                    "enabled=${s.optBoolean("enabled")} gps=${s.optBoolean("gps")} " +
                        "network=${s.optBoolean("network")} status=${s.optInt("status")}"
                )
            }
        }
        val f = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction("android.location.MODE_CHANGED")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, f)
            }
            receiver = r
        }
    }

    fun stop(ctx: Context) {
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
    }
}


/**
 * `schedule` giống lib gốc: mảng chuỗi dạng `"<thứ> <giờ>"`.
 *   "1-5 08:00-12:00,13:30-17:30"  (1 = Thứ 2 … 7 = Chủ nhật)
 *   "6,7 09:00-11:00"
 * Không có schedule (hoặc mảng rỗng) = chạy 24/7.
 */
object Schedule {

    fun configured(ctx: Context): Boolean {
        val a = Prefs.config(ctx).optJSONArray("schedule") ?: return false
        return a.length() > 0
    }

    fun spec(ctx: Context): JSONArray =
        Prefs.config(ctx).optJSONArray("schedule") ?: JSONArray()

    fun isActive(ctx: Context, now: Calendar = Calendar.getInstance()): Boolean {
        val arr = Prefs.config(ctx).optJSONArray("schedule") ?: return true
        if (arr.length() == 0) return true
        val day = mondayFirst(now)
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        for (i in 0 until arr.length()) {
            if (matches(arr.optString(i), day, minute)) return true
        }
        return false
    }

    private fun mondayFirst(c: Calendar): Int {
        val d = c.get(Calendar.DAY_OF_WEEK) // 1 = CN
        return if (d == Calendar.SUNDAY) 7 else d - 1
    }

    private fun matches(spec: String, day: Int, minute: Int): Boolean {
        val parts = spec.trim().split(Regex("\\s+"))
        if (parts.size < 2) return false
        if (!daysMatch(parts[0], day)) return false
        return parts.drop(1).any { range ->
            val m = Regex("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})").matchEntire(range.trim())
            if (m == null) false else {
                val from = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
                var to = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
                if (to <= from) to += 24 * 60 // qua nửa đêm
                minute in from until to
            }
        }
    }

    private fun daysMatch(spec: String, day: Int): Boolean =
        spec.split(",").any { part ->
            val m = Regex("(\\d)-?(\\d)?").matchEntire(part.trim())
            if (m == null) false else {
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
                day in a..b
            }
        }
}


/** `getDeviceInfo` / `getSensors`. */
object DeviceInfo {

    fun json(ctx: Context): JSONObject = JSONObject().apply {
        put("platform", "android")
        put("model", Build.MODEL)
        // `name` PHẢI TRÙNG ĐÚNG giá trị đang gửi lên server trong `device_name` của mọi bản ghi
        // (location / diag / geofence) — xem Util.DeviceId.name() = Build.MODEL.
        // App đọc field này để đóng dấu tên máy vào bản ghi chấm công. Thiếu nó (đã xảy ra thật
        // 27/10/2026): app gửi device_name rỗng nên bảng chấm công phải hiện mã máy thay vì tên
        // máy (SM-A066B), trong khi /devices vẫn biết tên → hai nguồn lệch nhau.
        put("name", Util.DeviceId.name())
        put("manufacturer", Build.MANUFACTURER)
        put("version", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("device", Build.DEVICE)
        put("brand", Build.BRAND)
        put("product", Build.PRODUCT)
        put("framework", "react-native")
        // Mã ổn định ĐÚNG LÀ mã đang gửi lên server trong từng bản ghi vị trí.
        //
        // VÌ SAO PHẢI CÓ (đã gây lỗi thật 22/10): app lab đọc `dev.device_id` từ hàm này
        // để đẩy định nghĩa geofence lên server. Android chỉ trả `identifier` nên giá trị
        // luôn rỗng → server lưu định nghĩa vùng vào nhóm "unknown". Hệ quả: vùng "Cty"
        // của SM-A127F và vùng "Cty" của iPhone cùng tồn tại, viewer vẽ 2 vòng chồng nhau.
        put("device_id", Util.DeviceId.id(ctx))
        put(
            "identifier",
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        )
    }

    fun sensors(ctx: Context): JSONArray {
        val out = JSONArray()
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return out
        sm.getSensorList(Sensor.TYPE_ALL).forEach { s ->
            out.put(
                JSONObject().apply {
                    put("name", s.name)
                    put("type", s.type)
                    put("vendor", s.vendor)
                    put("version", s.version)
                    put("power", s.power.toDouble())
                    put("maxRange", s.maximumRange.toDouble())
                    put("resolution", s.resolution.toDouble())
                }
            )
        }
        return out
    }
}

/** `playSound` — beep ngắn để debug (giống lib gốc). */
object Sound {
    private val handler = Handler(Looper.getMainLooper())

    fun beep(kind: String) {
        runCatching {
            val tone = when (kind) {
                "motion" -> ToneGenerator.TONE_PROP_ACK
                "error" -> ToneGenerator.TONE_PROP_NACK
                else -> ToneGenerator.TONE_PROP_BEEP
            }
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(tone, 150)
            handler.postDelayed({ runCatching { tg.release() } }, 400)
        }
    }
}
