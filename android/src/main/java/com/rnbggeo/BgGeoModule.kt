package com.rnbggeo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject

class BgGeoModule(private val reactCtx: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactCtx) {

    companion object {
        const val NAME = "BgGeo"
        private const val EVENT_PROXY = "rnbggeo:proxy"

        /**
         * Số bản ghi tối đa trả về cho getLocations() (lịch sử để vẽ tuyến).
         * 2.000 điểm ≈ 4 ngày đi lại bình thường, đủ cho UI mà không nghẽn bridge.
         */
        private const val HISTORY_LIMIT = 2_000

        /** Khởi tạo lại phần native (gọi từ BootReceiver và ready()) */
        fun reregisterNative(ctx: Context) {
            val cfg = Prefs.config(ctx)
            if (!cfg.optBoolean("motionDetection", true)) return
            try {
                val client = ActivityRecognition.getClient(ctx)
                val pi = android.app.PendingIntent.getBroadcast(
                    ctx, 1,
                    android.content.Intent(ctx, MotionReceiver::class.java).setAction(MotionReceiver.ACTION),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                client.requestActivityTransitionUpdates(
                    MotionReceiver.buildRequest(), pi
                ).addOnSuccessListener {
                    Diag.log("activity transition updates registered")
                }.addOnFailureListener { e ->
                    Diag.log("activity transition register FAILED: ${e.message}")
                }
            } catch (e: Exception) {
                Diag.log("reregisterNative motion failed: ${e.message}")
            }
            // Geofence cần đăng ký lại sau reboot
            val list = ArrayList<JSONObject>()
            val arr = Prefs.geofences(ctx)
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            Geofences.register(ctx, LocationServices.getGeofencingClient(ctx), list)
        }
    }

    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(reactCtx)
    private val geofencing: GeofencingClient =
        LocationServices.getGeofencingClient(reactCtx)

    override fun getName(): String = NAME

    init {
        Events.reactContext = reactCtx
        registerSystemListeners()
    }

    override fun invalidate() {
        unregisterSystemListeners()
        Events.reactContext = null
        super.invalidate()
    }

    // ------------------------------------------------- sự kiện hệ thống (đăng ký ĐỘNG)
    // Từ Android 8 (API 26), receiver khai báo trong manifest KHÔNG nhận được
    // implicit broadcast (CONNECTIVITY_CHANGE, POWER_SAVE_MODE_CHANGED...).
    // Đăng ký động trong module thì không bị giới hạn đó.

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var powerSaveReceiver: BroadcastReceiver? = null

    /** Trạng thái mạng lần cuối đã bắn — tránh spam vì onCapabilitiesChanged gọi rất thường xuyên. */
    private var lastNetKey: String? = null

    private fun registerSystemListeners() {
        if (netCallback == null) {
            try {
                val cm = ctx().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = emitConnectivity("available")
                    override fun onLost(network: Network) = emitConnectivity("lost")
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                        emitConnectivity("caps")
                }
                cm.registerDefaultNetworkCallback(cb)
                netCallback = cb
                Diag.log("connectivity listener registered (NetworkCallback)")
            } catch (e: Exception) {
                Diag.log("connectivity listener FAILED: ${e.message}")
            }
        }
        if (powerSaveReceiver == null) {
            try {
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) = emitPowerSave("change")
                }
                val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx().registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    ctx().registerReceiver(r, filter)
                }
                powerSaveReceiver = r
                Diag.log("powersave listener registered (dynamic receiver)")
            } catch (e: Exception) {
                Diag.log("powersave listener FAILED: ${e.message}")
            }
        }
    }

    private fun unregisterSystemListeners() {
        netCallback?.let {
            try {
                val cm = ctx().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {}
        }
        netCallback = null
        powerSaveReceiver?.let {
            try { ctx().unregisterReceiver(it) } catch (e: Exception) {}
        }
        powerSaveReceiver = null
    }

    private fun emitConnectivity(reason: String) {
        try {
            val status = ConnectivityReceiver.currentStatus(ctx())
            // chỉ bắn khi connected/type THẬT SỰ đổi (NetworkCallback gọi rất nhiều lần)
            val key = "${status.optBoolean("connected")}|${status.optString("type")}"
            if (key == lastNetKey) return
            lastNetKey = key
            val msg = if (status.optBoolean("connected"))
                "mạng có lại (${status.optString("type")})" else "mất mạng"
            Diag.event(ctx(), "connectivity", "$msg [$reason]", status)
            Events.emit(ctx(), Events.CONNECTIVITY, status.toString())
        } catch (e: Exception) {
            Diag.log("emitConnectivity failed: ${e.message}")
        }
    }

    private fun emitPowerSave(reason: String) {
        try {
            val pm = ctx().getSystemService(Context.POWER_SERVICE) as PowerManager
            val mode = pm.isPowerSaveMode
            val o = JSONObject()
            o.put("isPowerSaveMode", mode)
            Diag.event(
                ctx(), "powersave",
                (if (mode) "chế độ tiết kiệm pin BẬT" else "chế độ tiết kiệm pin TẮT") + " [$reason]",
                o
            )
            Events.emit(ctx(), Events.POWERSAVE, o.toString())
        } catch (e: Exception) {
            Diag.log("emitPowerSave failed: ${e.message}")
        }
    }

    // JS listener bookkeeping (NativeEventEmitter contract)
    private var listenerCount = 0
    @ReactMethod fun addListener(eventName: String) { listenerCount++ }
    @ReactMethod fun removeListeners(count: Double) { listenerCount = maxOf(0, listenerCount - count.toInt()) }

    private fun ctx(): Context = reactCtx.applicationContext

    private fun toJson(map: ReadableMap): JSONObject {
        val jo = JSONObject()
        val it = map.keySetIterator()
        while (it.hasNextKey()) {
            val k = it.nextKey()
            when (map.getType(k)) {
                com.facebook.react.bridge.ReadableType.Null -> jo.put(k, JSONObject.NULL)
                com.facebook.react.bridge.ReadableType.Boolean -> jo.put(k, map.getBoolean(k))
                com.facebook.react.bridge.ReadableType.Number -> jo.put(k, map.getDouble(k))
                com.facebook.react.bridge.ReadableType.String -> jo.put(k, map.getString(k))
                com.facebook.react.bridge.ReadableType.Map -> jo.put(k, toJson(map.getMap(k)!!))
                com.facebook.react.bridge.ReadableType.Array -> jo.put(k, toJsonArray(map.getArray(k)!!))
            }
        }
        return jo
    }

    private fun toJsonArray(arr: ReadableArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.size()) {
            when (arr.getType(i)) {
                com.facebook.react.bridge.ReadableType.Null -> out.put(JSONObject.NULL)
                com.facebook.react.bridge.ReadableType.Boolean -> out.put(arr.getBoolean(i))
                com.facebook.react.bridge.ReadableType.Number -> out.put(arr.getDouble(i))
                com.facebook.react.bridge.ReadableType.String -> out.put(arr.getString(i))
                com.facebook.react.bridge.ReadableType.Map -> out.put(toJson(arr.getMap(i)!!))
                com.facebook.react.bridge.ReadableType.Array -> out.put(toJsonArray(arr.getArray(i)!!))
            }
        }
        return out
    }

    @ReactMethod
    fun ready(config: ReadableMap, promise: Promise) {
        val cfg = toJson(config)
        val merged = JSONObject(Prefs.config(ctx()).toString()).apply {
            cfg.keys().forEach { k -> put(k, cfg.opt(k)) }
        }
        Prefs.saveConfig(ctx(), merged)
        Diag.configure(ctx(), merged)
        HttpUploader.registerConnectivityListener(ctx())
        // theo dõi GPS bật/tắt + quyền (onProviderChange / onAuthorization)
        ProviderWatcher.start(ctx())
        // native re-register cho boot / app restart
        if (merged.optBoolean("motionDetection", true)) reregisterNative(ctx())

        // Nâng cấp từ bản cũ chưa có cờ `desired`: trước đó service đang chạy thì
        // coi như người dùng vẫn muốn theo dõi, tránh việc app "quên" bật lại.
        if (Prefs.enabled(ctx()) && !Prefs.desired(ctx())) Prefs.setDesired(ctx(), true)

        Diag.event(
            ctx(), "ready",
            "ready() xong — logToFile=${merged.optBoolean("logToFile", false)} " +
                "postDiagEvents=${merged.optBoolean("postDiagEvents", false)} " +
                "desired=${Prefs.desired(ctx())} geofences=${Prefs.geofences(ctx()).length()}"
        )

        // Lúc này service chưa chạy — đặt lại enabled để trạng thái không nói dối.
        Prefs.setEnabled(ctx(), false)
        if (Prefs.desired(ctx()) && !merged.optBoolean("stopOnTerminate", false)) {
            TrackingService.start(ctx())
        }
        promise.resolve(stateJson())
    }

    @ReactMethod
    fun start(promise: Promise) {
        Prefs.setDesired(ctx(), true)
        Diag.event(ctx(), "api", "start() — người dùng bật theo dõi")
        TrackingService.start(ctx())
        if (Prefs.config(ctx()).optBoolean("motionDetection", true)) reregisterNative(ctx())
        promise.resolve(stateJson())
    }

    @ReactMethod
    fun stop(promise: Promise) {
        Prefs.setDesired(ctx(), false)
        Prefs.setEnabled(ctx(), false)
        Diag.event(ctx(), "api", "stop() — người dùng tắt theo dõi")
        TrackingService.stop(ctx())
        try {
            val pi = android.app.PendingIntent.getBroadcast(
                ctx(), 1,
                android.content.Intent(ctx(), MotionReceiver::class.java).setAction(MotionReceiver.ACTION),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            ActivityRecognition.getClient(ctx()).removeActivityTransitionUpdates(pi)
        } catch (e: Exception) {}
        promise.resolve(stateJson())
    }

    @ReactMethod
    fun setConfig(config: ReadableMap, promise: Promise) {
        val cfg = toJson(config)
        val merged = JSONObject(Prefs.config(ctx()).toString()).apply {
            cfg.keys().forEach { k -> put(k, cfg.opt(k)) }
        }
        Prefs.saveConfig(ctx(), merged)
        Diag.configure(ctx(), merged)
        Diag.event(ctx(), "api", "setConfig(${cfg.keys().asSequence().joinToString(",")})")
        // nếu đang chạy thì báo service kiểm tra lại cấu hình; service chỉ đăng ký lại
        // GPS khi các khoá ảnh hưởng tới LocationRequest thật sự đổi.
        if (isServiceRunning()) {
            TrackingService.start(ctx())
        }
        promise.resolve(stateJson())
    }

    @ReactMethod
    fun getState(promise: Promise) = promise.resolve(stateJson())

    @ReactMethod
    fun getCurrentPosition(options: ReadableMap?, promise: Promise) {
        val priority = when (options?.getString("desiredAccuracy")) {
            "low" -> Priority.PRIORITY_LOW_POWER
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            else -> Priority.PRIORITY_HIGH_ACCURACY
        }
        val cancellation = com.google.android.gms.tasks.CancellationTokenSource()
        // `options.timeout` tính bằng ms (giữ tương thích cũ). Không truyền thì lấy
        // `locationTimeout` trong cấu hình — đơn vị GIÂY, mặc định 60, giống transistorsoft.
        val timeoutMs = if (options != null && options.hasKey("timeout")) {
            options.getDouble("timeout")
        } else {
            Prefs.config(ctx()).optDouble("locationTimeout", 60.0) * 1000.0
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            cancellation.cancel()
        }, timeoutMs.toLong())
        try {
            fused.getCurrentLocation(priority, cancellation.token)
                .addOnSuccessListener { loc: Location? ->
                    if (loc == null) {
                        promise.reject("E_NO_LOCATION", "Không lấy được vị trí")
                        return@addOnSuccessListener
                    }
                    val bm = ctx().getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    val json = Util.toJson(
                        loc, Prefs.activity(ctx()), 100,
                        Prefs.isMoving(ctx()), "location",
                        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100f,
                        false, Prefs.odometer(ctx()), false
                    )
                    promise.resolve(Arguments.fromBundle(android.os.Bundle().apply {
                        putString("location", json.toString())
                    }))
                }
                .addOnFailureListener { e ->
                    promise.reject("E_LOCATION", e.message, e)
                }
        } catch (e: SecurityException) {
            promise.reject("E_PERMISSION", "Thiếu quyền vị trí", e)
        }
    }

    @ReactMethod
    fun changePace(isMoving: Boolean, promise: Promise) {
        Prefs.setMoving(ctx(), isMoving)
        // false = giả lập đứng yên → tắt GPS nhưng GIỮ service (đừng dùng stop() vì stop là tắt hẳn)
        if (isMoving) TrackingService.start(ctx()) else TrackingService.pause(ctx())
        promise.resolve(stateJson())
    }

    @ReactMethod
    fun addGeofence(g: ReadableMap, promise: Promise) {
        val j = toJson(g).apply {
            if (!has("identifier") || optString("identifier").isEmpty()) put("identifier", Util.uuid())
        }
        validateGeofence(j)?.let { msg ->
            promise.reject("E_GEOFENCE", msg)
            return
        }
        val arr = JSONArray(Prefs.geofences(ctx()).toString())
        arr.put(j)
        Prefs.saveGeofences(ctx(), arr)
        Diag.log("addGeofence lưu xong — tổng ${Prefs.geofences(ctx()).length()} vùng")
        Geofences.register(ctx(), geofencing, listOf(j))
        emitGeofencesChange()
        initialTriggerIfInside(j)
        promise.resolve(j.toString())
    }

    @ReactMethod
    fun addGeofences(list: ReadableArray, promise: Promise) {
        val arr = JSONArray(Prefs.geofences(ctx()).toString())
        val out = ArrayList<JSONObject>()
        for (i in 0 until list.size()) {
            // KHÔNG dùng `list.getMap(i)!!`: phần tử không phải object (null / undefined / số)
            // sẽ ném NullPointerException và LÀM CHẾT APP. Từ chối bằng promise.reject để app
            // tự xử lý (JS ở iOS không có vấn đề này vì `as? [String: Any]` chỉ trả nil).
            val item = list.getMap(i)
            if (item == null) {
                promise.reject(
                    "E_GEOFENCE",
                    "vùng #$i không phải object (nhận ${list.getType(i).toString().lowercase()})"
                )
                return
            }
            val j = toJson(item).apply {
                if (!has("identifier") || optString("identifier").isEmpty()) put("identifier", Util.uuid())
            }
            validateGeofence(j)?.let { msg ->
                promise.reject("E_GEOFENCE", "vùng #$i: $msg")
                return
            }
            arr.put(j)
            out.add(j)
        }
        Prefs.saveGeofences(ctx(), arr)
        Geofences.register(ctx(), geofencing, out)
        emitGeofencesChange()
        out.filter { GeoMath.isPolygon(it) }.forEach { initialTriggerIfInside(it) }
        promise.resolve(out.size)
    }

    /**
     * `geofenceInitialTriggerEntry` (mặc định true): nếu vừa thêm vùng ĐA GIÁC mà
     * vị trí gần nhất (không quá 5 phút) đang nằm trong vùng thì bắn ENTER ngay —
     * giống `INITIAL_TRIGGER_ENTER` mà Android làm cho vùng tròn.
     */
    private fun initialTriggerIfInside(j: JSONObject) {
        if (!GeoMath.isPolygon(j)) return
        if (!Prefs.config(ctx()).optBoolean("geofenceInitialTriggerEntry", true)) return
        val last = Prefs.lastLocation(ctx()) ?: return
        val age = System.currentTimeMillis() - last.optLong("timestamp", 0L)
        if (age !in 0..300_000L) return
        val c = last.optJSONObject("coords") ?: return
        Polygons.evaluate(ctx(), c.optDouble("latitude"), c.optDouble("longitude"), System.currentTimeMillis(), last)
    }

    /**
     * Kiểm tra một geofence trước khi lưu.
     * - Có `vertices` → geofence đa giác; tự tính center/radius bao.
     * - Không có → geofence tròn, bắt buộc lat/lng/radius.
     * Trả về thông báo lỗi hoặc null nếu hợp lệ.
     */
    private fun validateGeofence(j: JSONObject): String? {
        if (GeoMath.isPolygon(j)) {
            if (!Polygons.normalize(j)) {
                return "vertices cần >= 3 cặp [lat,lng] hợp lệ và diện tích > 0"
            }
            Diag.event(
                ctx(), "api",
                "addGeofence đa giác ${j.optString("identifier")} — " +
                    "${GeoMath.vertices(j)?.size} đỉnh, vòng bao ${j.optDouble("radius").toInt()}m"
            )
            return null
        }
        if (!j.has("latitude") || !j.has("longitude")) return "thiếu latitude/longitude"
        if (j.optDouble("radius", 0.0) <= 0.0) return "radius phải > 0"
        return null
    }

    @ReactMethod
    fun removeGeofence(identifier: String, promise: Promise) {
        val arr = Prefs.geofences(ctx())
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            if (j.optString("identifier") != identifier) out.put(j)
        }
        Prefs.saveGeofences(ctx(), out)
        try { geofencing.removeGeofences(listOf(identifier)) } catch (e: Exception) {}
        Prefs.setActiveGeofences(ctx(), Prefs.activeGeofences(ctx()) - identifier)
        Polygons.forget(ctx(), identifier)
        emitGeofencesChange()
        promise.resolve(true)
    }

    @ReactMethod
    fun removeGeofences(ids: ReadableArray?, promise: Promise) {
        val drop = HashSet<String>()
        if (ids != null) {
            for (i in 0 until ids.size()) {
                ids.getString(i)?.let { drop.add(it) }
            }
        }
        if (drop.isEmpty()) {
            Prefs.saveGeofences(ctx(), JSONArray())
            // phải gỡ bằng PendingIntent, removeGeofences(listOf()) là no-op
            try { geofencing.removeGeofences(GeofenceReceiver.pendingIntent(ctx())) } catch (e: Exception) {}
            Prefs.setActiveGeofences(ctx(), emptySet())
            Polygons.forgetAll(ctx())
        } else {
            val old = Prefs.geofences(ctx())
            val next = JSONArray()
            for (i in 0 until old.length()) {
                val j = old.optJSONObject(i) ?: continue
                if (j.optString("identifier") !in drop) next.put(j)
            }
            Prefs.saveGeofences(ctx(), next)
            runCatching { geofencing.removeGeofences(drop.toList()) }
            Prefs.setActiveGeofences(ctx(), Prefs.activeGeofences(ctx()) - drop)
            drop.forEach { Polygons.forget(ctx(), it) }
        }
        emitGeofencesChange()
        promise.resolve(true)
    }

    @ReactMethod
    fun getGeofences(promise: Promise) {
        val arr = Prefs.geofences(ctx())
        Diag.log("getGeofences → ${arr.length()} vùng")
        promise.resolve(arr.toString())
    }

    @ReactMethod
    fun getLocations(promise: Promise) {
        // cũ → mới, đúng thứ tự thời gian để vẽ tuyến đường
        promise.resolve(LocationDb.get(ctx()).history(HISTORY_LIMIT).toString())
    }

    @ReactMethod
    fun getCount(promise: Promise) {
        promise.resolve(LocationDb.get(ctx()).count())
    }

    @ReactMethod
    fun sync(promise: Promise) {
        HttpUploader.registerConnectivityListener(ctx())
        promise.resolve(HttpUploader.sync(ctx()))
    }

    @ReactMethod
    fun destroyLocations(promise: Promise) {
        LocationDb.get(ctx()).destroy()
        Diag.event(ctx(), "api", "destroyLocations() — xoá toàn bộ lịch sử")
        promise.resolve(true)
    }

    // ---------------------------------------------------------------- nhật ký chẩn đoán

    /** Đường dẫn file log (rỗng nếu chưa bật logToFile). */
    @ReactMethod
    fun getLogPath(promise: Promise) = promise.resolve(Diag.logPath())

    @ReactMethod
    fun getLogSize(promise: Promise) = promise.resolve(Diag.logSize().toDouble())

    /** Đọc phần cuối file log (tối đa `maxBytes`). */
    @ReactMethod
    fun getLog(maxBytes: Double, promise: Promise) {
        val n = if (maxBytes > 0) maxBytes.toInt() else 200_000
        promise.resolve(Diag.tail(n))
    }

    @ReactMethod
    fun clearLog(promise: Promise) {
        Diag.clear()
        promise.resolve(true)
    }

    /**
     * Đẩy file log chẩn đoán lên server (`<url>/logs`).
     *
     * Nhờ vậy đọc được log của máy ở xa mà không cần cắm cáp `adb pull` — đúng nhu cầu
     * khi chẩn đoán SM-A127F ngày 23/10. Trả về chuỗi JSON
     * `{ok, status, response, bytes, logPath}`.
     */
    @ReactMethod
    fun uploadLog(maxBytes: Double, promise: Promise) {
        val n = if (maxBytes > 0) maxBytes.toInt() else 200_000
        Diag.event(ctx(), "api", "uploadLog() — đẩy nhật ký lên server ($n byte cuối)")
        HttpUploader.uploadLog(ctx(), n) { ok, status, response ->
            val o = JSONObject()
            o.put("ok", ok)
            o.put("status", status)
            o.put("response", response)
            o.put("bytes", Diag.logSize())
            o.put("logPath", Diag.logPath())
            promise.resolve(o.toString())
        }
    }

    @ReactMethod
    fun hasPermission(promise: Promise) {
        val fine = ContextCompat.checkSelfPermission(ctx(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(ctx(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val o = JSONObject()
        o.put("fine", fine)
        o.put("background", background)
        promise.resolve(o.toString())
    }

    // ---------------------------------------------------------- odometer

    @ReactMethod
    fun getOdometer(promise: Promise) {
        promise.resolve(Prefs.odometer(ctx()))
    }

    @ReactMethod
    fun resetOdometer(promise: Promise) {
        Prefs.resetOdometer(ctx())
        Diag.event(ctx(), "api", "resetOdometer() — đặt lại odometer về 0")
        promise.resolve(true)
    }

    // ---------------------------------------------------------- geofence stats

    @ReactMethod
    fun getGeofenceStats(promise: Promise) {
        val arr = Prefs.geofences(ctx())
        val o = JSONObject()
        o.put("count", arr.length())
        o.put("max", 100)
        promise.resolve(o.toString())
    }

    // ---------------------------------------------------------- power save

    @ReactMethod
    fun isPowerSaveMode(promise: Promise) {
        val pm = ctx().getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        promise.resolve(pm.isPowerSaveMode)
    }

    // ---------------------------------------------------------- connectivity

    @ReactMethod
    fun getConnectivityInfo(promise: Promise) {
        promise.resolve(ConnectivityReceiver.currentStatus(ctx()).toString())
    }

    // ---------------------------------------------------------- background task (iOS parity)

    @ReactMethod
    fun startBackgroundTask(promise: Promise) {
        // Android không cần — foreground service đã đảm bảo. Trả ID giả cho parity.
        promise.resolve(0)
    }

    @ReactMethod
    fun endBackgroundTask(taskId: Double, promise: Promise) {
        promise.resolve(true)
    }

    // ---------------------------------------------------------- watch position

    @Volatile
    private var watchCallback: com.google.android.gms.location.LocationCallback? = null

    @ReactMethod
    fun startWatchPosition(options: ReadableMap?, promise: Promise) {
        val opts = options ?: Arguments.createMap()
        val priority = when (opts.getString("desiredAccuracy")) {
            "low" -> Priority.PRIORITY_LOW_POWER
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            "passive" -> Priority.PRIORITY_PASSIVE
            else -> Priority.PRIORITY_HIGH_ACCURACY
        }
        val interval = if (opts.hasKey("interval")) opts.getDouble("interval").toLong() else 5000L
        val distanceFilter = if (opts.hasKey("distanceFilter")) opts.getDouble("distanceFilter").toFloat() else 0f

        // gỡ callback cũ nếu có
        watchCallback?.let { runCatching { fused.removeLocationUpdates(it) } }

        val cb = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.lastLocation?.let { loc ->
                    val bm = ctx().getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    val json = Util.toJson(
                        loc, Prefs.activity(ctx()), 100,
                        Prefs.isMoving(ctx()), "watch",
                        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100f,
                        false, Prefs.odometer(ctx()), false
                    )
                    Events.emit(ctx(), Events.LOCATION, json.toString())
                }
            }
        }
        watchCallback = cb

        val request = LocationRequest.Builder(priority, interval)
            .setMinUpdateDistanceMeters(distanceFilter)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fused.requestLocationUpdates(request, cb, ctx().mainLooper)
            Diag.event(ctx(), "api", "startWatchPosition() interval=${interval}ms")
            promise.resolve(true)
        } catch (e: SecurityException) {
            promise.reject("E_PERMISSION", "Thiếu quyền vị trí", e)
        }
    }

    @ReactMethod
    fun stopWatchPosition(promise: Promise) {
        watchCallback?.let {
            runCatching { fused.removeLocationUpdates(it) }
            watchCallback = null
        }
        Diag.event(ctx(), "api", "stopWatchPosition()")
        promise.resolve(true)
    }

    /** Service có đang chạy thật hay không — nguồn sự thật cho `enabled`. */
    private fun isServiceRunning(): Boolean {
        return try {
            val am = ctx().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == TrackingService::class.java.name
            }
        } catch (e: Exception) {
            Prefs.enabled(ctx())
        }
    }

    /** Bắn event onGeofencesChange lên JS khi add/remove geofence. */
    private fun emitGeofencesChange() {
        val arr = Prefs.geofences(ctx())
        val o = JSONObject()
        o.put("count", arr.length())
        o.put("geofences", arr)
        o.put("timestamp", System.currentTimeMillis())
        Events.emit(ctx(), Events.GEOFENCES_CHANGE, o.toString())
    }

    // ==================================================================
    // API bổ sung để tiệm cận transistorsoft (đợt 20/10/2026)
    // ==================================================================

    /** Toàn bộ cấu hình hiện tại (giống getConfig của lib gốc). */
    @ReactMethod
    fun getConfig(promise: Promise) = promise.resolve(Prefs.config(ctx()).toString())

    /** Gán giá trị odometer. */
    @ReactMethod
    fun setOdometer(value: Double, promise: Promise) {
        Prefs.setOdometer(ctx(), value)
        Diag.event(ctx(), "api", "setOdometer(${value.toInt()}m)")
        promise.resolve(value)
    }

    /** Chèn một điểm thủ công vào DB (giống insertLocation). */
    @ReactMethod
    fun insertLocation(g: ReadableMap, promise: Promise) {
        val o = toJson(g)
        if (!o.has("uuid") || o.optString("uuid").isEmpty()) o.put("uuid", Util.uuid())
        if (!o.has("timestamp")) o.put("timestamp", System.currentTimeMillis())
        if (!o.has("event")) o.put("event", "location")
        if (!o.has("is_moving")) o.put("is_moving", false)
        if (!o.has("activity")) o.put("activity", JSONObject().put("type", "unknown").put("confidence", 0))
        if (!o.has("battery")) o.put("battery", JSONObject().put("level", 0).put("is_charging", false))
        if (!o.has("odometer")) o.put("odometer", Prefs.odometer(ctx()))
        if (!o.has("mock")) o.put("mock", false)
        if (!o.has("coords")) {
            promise.reject("E_LOCATION", "insertLocation cần coords{latitude,longitude}")
            return
        }
        Util.tagDevice(o, ctx())
        val ok = LocationDb.get(ctx()).insert(o)
        if (ok) {
            Diag.event(ctx(), "api", "insertLocation ${o.optString("uuid")}")
        }
        promise.resolve(if (ok) o.optString("uuid") else "")
    }

    /** Xoá một điểm theo uuid. */
    @ReactMethod
    fun destroyLocation(uuid: String, promise: Promise) {
        promise.resolve(LocationDb.get(ctx()).deleteOne(uuid))
    }

    /** Lấy một geofence theo identifier (null nếu không có). */
    @ReactMethod
    fun getGeofence(identifier: String, promise: Promise) {
        val arr = Prefs.geofences(ctx())
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            if (j.optString("identifier") == identifier) {
                promise.resolve(j.toString())
                return
            }
        }
        promise.resolve(null)
    }

    @ReactMethod
    fun geofenceExists(identifier: String, promise: Promise) {
        val arr = Prefs.geofences(ctx())
        var found = false
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("identifier") == identifier) {
                found = true
                break
            }
        }
        promise.resolve(found)
    }

    /** Thông tin thiết bị (getDeviceInfo). */
    @ReactMethod
    fun getDeviceInfo(promise: Promise) = promise.resolve(DeviceInfo.json(ctx()).toString())

    /** Danh sách cảm biến (getSensors). */
    @ReactMethod
    fun getSensors(promise: Promise) = promise.resolve(DeviceInfo.sensors(ctx()).toString())

    /** Reset SDK rồi áp cấu hình mới (giống reset(config)). */
    @ReactMethod
    fun reset(config: ReadableMap, promise: Promise) {
        TrackingService.stop(ctx())
        Prefs.setDesired(ctx(), false)
        Prefs.clearConfig(ctx())
        val fresh = toJson(config)
        Prefs.saveConfig(ctx(), fresh)
        Diag.configure(ctx(), fresh)
        Diag.event(ctx(), "api", "reset() — xoá cấu hình cũ, áp cấu hình mới")
        promise.resolve(stateJson())
    }

    /** Trạng thái GPS/quyền (getProviderState). */
    @ReactMethod
    fun getProviderState(promise: Promise) =
        promise.resolve(ProviderWatcher.stateJson(ctx()).toString())

    @ReactMethod
    fun setLogLevel(level: Double, promise: Promise) {
        Diag.setLevel(level.toInt())
        promise.resolve(Diag.getLevel())
    }

    @ReactMethod
    fun playSound(kind: String, promise: Promise) {
        Sound.beep(kind)
        promise.resolve(true)
    }

    @ReactMethod
    fun destroyLog(promise: Promise) {
        Diag.destroy()
        promise.resolve(true)
    }

    @ReactMethod
    fun getSchedule(promise: Promise) {
        val o = JSONObject()
        o.put("enabled", Prefs.scheduleEnabled(ctx()) && Schedule.configured(ctx()))
        o.put("spec", Schedule.spec(ctx()))
        o.put("active", Schedule.isActive(ctx()))
        promise.resolve(o.toString())
    }

    @ReactMethod
    fun startSchedule(promise: Promise) {
        Prefs.setScheduleEnabled(ctx(), true)
        Diag.event(ctx(), "schedule", "startSchedule()")
        if (Schedule.isActive(ctx())) {
            Prefs.setDesired(ctx(), true)
            TrackingService.start(ctx())
        }
        getSchedule(promise)
    }

    @ReactMethod
    fun stopSchedule(promise: Promise) {
        Prefs.setScheduleEnabled(ctx(), false)
        Diag.event(ctx(), "schedule", "stopSchedule()")
        getSchedule(promise)
    }

    private fun stateJson(): String {
        val cfg = Prefs.config(ctx())
        val o = JSONObject()
        o.put("enabled", isServiceRunning())
        o.put("desired", Prefs.desired(ctx()))
        o.put("stationary", Prefs.stationary(ctx()))
        o.put("trackingMode", if (Prefs.isMoving(ctx())) 1 else 0)
        o.put("isMoving", Prefs.isMoving(ctx()))
        o.put("activity", Prefs.activity(ctx()))
        o.put("odometer", Prefs.odometer(ctx()))
        o.put("geofenceCount", Prefs.geofences(ctx()).length())
        o.put("insideFenceCount", Prefs.insideFences(ctx()).size)
        o.put("pauseInsideGeofence", cfg.optBoolean("pauseInsideGeofence", false))
        o.put("zeroLossMode", cfg.optBoolean("zeroLossMode", false))
        o.put("pendingCount", LocationDb.get(ctx()).count())
        o.put("totalCount", LocationDb.get(ctx()).total())
        o.put("distanceFilter", cfg.optDouble("distanceFilter", 10.0))
        o.put("interval", cfg.optLong("interval", 5000L))
        o.put("url", cfg.optString("url"))
        o.put("autoSync", cfg.optBoolean("autoSync", true))
        o.put("persist", cfg.optBoolean("persist", true))
        o.put("stopOnTerminate", cfg.optBoolean("stopOnTerminate", false))
        o.put("startOnBoot", cfg.optBoolean("startOnBoot", true))
        o.put("schedulerEnabled", false)
        o.put("logToFile", cfg.optBoolean("logToFile", false))
        o.put("logPath", Diag.logPath())
        o.put("logSize", Diag.logSize())
        o.put("postDiagEvents", cfg.optBoolean("postDiagEvents", false))
        // Số vệ tinh (Android) — để chẩn đoán "không thấy vệ tinh" hay "thấy mà không ra vị trí".
        // Xem GnssWatcher. iOS trả -1 vì không có API công khai.
        o.put("gnssUsed", GnssWatcher.used)
        o.put("gnssVisible", GnssWatcher.visible)
        o.put("gnssText", GnssWatcher.describe())
        return o.toString()
    }
}
