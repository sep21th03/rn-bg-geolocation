package com.rnbggeo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

class TrackingService : Service() {

    companion object {
        /** Kênh "chi tiết": importance LOW (không âm thanh, vẫn có icon trên status bar). */
        const val CHANNEL_ID = "rn_bg_geo_channel"

        /**
         * Kênh "tối giản": importance MIN → KHÔNG có icon trên thanh trạng thái,
         * chỉ nằm trong mục "Thông báo im lặng" khi kéo thanh thông báo xuống.
         * Android bắt buộc foreground service phải có notification, nhưng không
         * bắt buộc nó phải hiện icon.
         */
        const val CHANNEL_ID_MIN = "rn_bg_geo_channel_min"

        const val NOTIF_ID = 1337
        const val ACTION_START = "com.rnbggeo.action.START"
        const val ACTION_STOP = "com.rnbggeo.action.STOP"
        const val ACTION_PAUSE = "com.rnbggeo.action.PAUSE"
        const val ACTION_SYNC = "com.rnbggeo.action.SYNC"

        /** 15 phút ghi nhịp tim 1 lần. */
        private const val HEARTBEAT_MS = 15 * 60 * 1000L

        /** Dưới mức pin này (và không sạc) thì tự dừng theo dõi. */
        private const val LOW_BATTERY_CUTOFF = 8

        /**
         * Hai điểm GPS cách nhau quá lâu thì KHÔNG cộng vào odometer (xem chú thích trong onLocation).
         * 5 phút là ngưỡng an toàn: đi chậm nhất cũng không thể có khoảng trống dài hơn thế
         * mà vẫn là chuyển động liên tục.
         */
        private const val MAX_ODOMETER_GAP_MS = 5 * 60 * 1000L

        /**
         * Cho phép giờ-fix lệch về QUÁ KHỨ chừng này so với mốc trước (đồng hồ máy / GNSS
         * xê dịch vài giây). Chỉ để odometer không bị "đứt quãng" oan.
         */
        private const val MAX_CLOCK_SKEW_MS = 60_000L

        /** Không có mẫu mới trong bao lâu thì xả mẫu mà bộ lọc đang giữ. */
        private const val FLUSH_AFTER_MS = 30_000L

        /**
         * WATCHDOG "NHÀ CUNG CẤP VỊ TRÍ IM LẶNG" — bao lâu kiểm tra một lần.
         *
         * VÌ SAO CẦN (đo thật 23/10 trên SM-A127F): máy có request vị trí đang ACTIVE,
         * service vẫn sống (nhịp alive 15 phút đều), mạng tốt (post → 200) mà
         * FusedLocationProvider trả về **0 mẫu trong 10 phút** — đi mất 4,8 km không ghi
         * được điểm nào. Máy đó còn bị y hệt 2 lần nữa (22/10 17:31→17:40 = 9,0 phút và
         * 18:11→18:17 = 5,7 phút). Trước đây KHÔNG có gì phát hiện việc này: phải cắm cáp
         * đọc file log mới biết.
         */
        private const val PROVIDER_WATCHDOG_TICK_MS = 60_000L

        /** Không "đá" lại provider dồn dập hơn mức này (tránh tốn pin vô ích). */
        private const val PROVIDER_KICK_COOLDOWN_MS = 5 * 60 * 1000L

        /** Cập nhật notification tối đa 1 lần/phút — nội dung đổi liên tục sẽ làm thanh thông báo nhấp nháy. */
        private const val NOTIF_MIN_INTERVAL_MS = 60_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, TrackingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** Người dùng bấm DỪNG — tắt hẳn, không tự bật lại khi cử động. */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackingService::class.java))
        }

        /**
         * Tạm nghỉ vì đứng yên (tiết kiệm pin). KHÔNG đổi ý muốn của người dùng,
         * nên khi cử động trở lại service sẽ tự bật.
         */
        fun pause(ctx: Context) {
            val i = Intent(ctx, TrackingService::class.java).setAction(ACTION_PAUSE)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                ctx.stopService(Intent(ctx, TrackingService::class.java))
            }
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private val db by lazy { LocationDb.get(this) }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Bộ lọc mẫu thô — dựng lại khi cấu hình đổi. */
    private var filter: LocationFilter? = null
    private var filterVersion: Long = -1L
    private var lastNotifAt: Long = 0L
    private var lastNotifText: String = ""
    private var perfSamples: Int = 0
    private var perfTotalNs: Long = 0L

    /**
     * Lần cuối NHẬN ĐƯỢC một mẫu từ nhà cung cấp vị trí (elapsedRealtime ms). 0 = chưa có.
     * Cập nhật ở ĐẦU `onLocation()` — tức là "provider có giao gì cho ta hay không", không
     * phụ thuộc mẫu đó bị bộ lọc bỏ hay được ghi.
     */
    private var lastProviderSampleAt = 0L
    /** Số lần watchdog phải "đá" lại provider — vào log để biết máy nào hay bị. */
    private var providerKicks = 0
    private var lastProviderKickAt = 0L
    private var perfMaxNs: Long = 0L

    /** Nhiệt độ pin mới nhất (°C) và trạng thái giảm tải vì nóng máy. */
    private var lastBatteryTemp: Double = 0.0
    @Volatile private var thermalThrottled: Boolean = false
    private var lastSpeed: Double = 0.0

    /** Tốc độ của mẫu GPS thô gần nhất (kể cả mẫu bị bỏ) — dùng cho [walkingHint]. */
    private var lastSampleSpeed: Float = 0f

    /**
     * Bảo vệ máy khỏi nóng: pin ≥ thermalLimitC (mặc định 42°C, không sạc) thì giảm
     * GPS xuống balanced + 30 s + 50 m; hạ dưới thermalResumeC (39,5°C) thì khôi phục.
     * Chỉ là giảm tần suất, không tắt hẳn — dữ liệu vẫn liên tục.
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            lastBatteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val cfg = Prefs.config(this@TrackingService)
            if (!cfg.optBoolean("thermalProtection", true)) {
                if (thermalThrottled) {
                    thermalThrottled = false
                    applyThermalMode()
                }
                return
            }
            val limit = cfg.optDouble("thermalLimitC", 42.0)
            val resume = cfg.optDouble("thermalResumeC", 39.5)
            // Hysteresis: bật ở limit, chỉ tắt khi đã nguội dưới resume.
            val should = !charging &&
                (if (thermalThrottled) lastBatteryTemp >= resume else lastBatteryTemp >= limit)
            if (should != thermalThrottled) {
                thermalThrottled = should
                applyThermalMode()
            }
        }
    }

    /**
     * Chuỗi cấu hình của lần ĐĂNG KÝ GPS gần nhất. Dùng để biết khi `setConfig()`
     * đổi distanceFilter/interval/desiredAccuracy mà phải đăng ký lại — nếu không,
     * thay đổi chỉ có hiệu lực sau khi stop()/start().
     */
    private var requestKey: String = ""

    /**
     * GPS đang được đăng ký hay không.
     *
     * Cần cờ này vì `startTracking()` bị gọi từ nhiều đường: `onStartCommand` khi service
     * được tạo lại, `ready()` từ JS, và `BootReceiver`. Hai đường cùng chạy trong vài trăm
     * mili-giây là chuyện bình thường — nếu không chặn, LocationRequest bị đăng ký lại và
     * `filter.reset()` xoá mất mẫu bộ lọc đang giữ.
     */
    @Volatile
    private var tracking: Boolean = false

    /** Bậc elasticity đang áp dụng (bội số 5 m/s) — đổi bậc mới cần đăng ký lại LocationRequest. */
    private var elasticBucket: Int = -1

    /**
     * Trạng thái của chế độ "không ghi điểm trong vùng geofence".
     * - insideSinceMs: lần đầu phát hiện đang ở trong vùng (0 = đang ở ngoài).
     *
     * Trong vùng, GPS có thể trôi vài chục mét dù người dùng đã ngồi yên trong văn phòng.
     * Ta không ghi các mẫu này. Việc TẮT GPS khi đứng yên do StopDetector lo (stopTimeout
     * 5 phút) — giống lib gốc, không có mốc riêng theo geofence nữa.
     */
    private var insideSinceMs: Long = 0L

    /**
     * Gợi ý "có thể đang đi" từ Activity Recognition + tốc độ mẫu GPS gần nhất.
     *
     * Chỉ dùng để quyết định có BỎ mẫu trong vùng geofence hay không; khẳng định đang di
     * chuyển vẫn do [MovementTracker] làm bằng quãng đường thật.
     */
    private fun walkingHint(): Boolean {
        val act = Prefs.activity(this)
        val walking = act == "walking" || act == "on_foot" || act == "running"
        return walking && lastSampleSpeed >= 0.5f
    }

    /**
     * Watchdog khi GPS đang tạm nghỉ trong vùng: 10 phút/lần lấy MỘT mẫu balanced để
     * kiểm tra còn trong vùng không. Đây là lớp dự phòng cuối nếu cả geofence EXIT của
     * hệ điều hành lẫn significant-motion đều không bắn (máy tiết kiệm pin, đa giác).
     */
    private val insideWatchdogRunnable = Runnable { checkInsideWatchdog() }

    /**
     * `onHeartbeat` — mỗi `heartbeatInterval` giây (mặc định 60) bắn vị trí cuối + pin.
     * Khác với log "alive" 15 phút (chỉ ghi file) — cái này bắn sang JS như lib gốc.
     */
    private val heartbeatJs = object : Runnable {
        override fun run() {
            val cfg = Prefs.config(this@TrackingService)
            val secs = cfg.optLong("heartbeatInterval", 60L).coerceAtLeast(15L)
            try {
                val o = JSONObject()
                o.put("timestamp", System.currentTimeMillis())
                Prefs.lastLocation(this@TrackingService)?.let { o.put("location", it) }
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100.0)
                o.put("odometer", Prefs.odometer(this@TrackingService))
                Events.emit(applicationContext, Events.HEARTBEAT, o.toString())
            } catch (e: Exception) {
            }
            handler.postDelayed(this, secs * 1000L)
        }
    }

    /**
     * `schedule` — 60 giây/lần kiểm tra khung giờ. Vào khung → bật theo dõi;
     * ra khỏi khung → tạm nghỉ (service vẫn sống để tick tiếp).
     */
    private val scheduleTick = object : Runnable {
        override fun run() {
            val ctx = this@TrackingService
            if (Schedule.configured(ctx) && Prefs.scheduleEnabled(ctx)) {
                val active = Schedule.isActive(ctx)
                if (active && !Prefs.desired(ctx)) {
                    Diag.event(ctx, "schedule", "vào khung giờ → bật theo dõi")
                    Prefs.setDesired(ctx, true)
                    startTracking()
                } else if (!active && Prefs.desired(ctx)) {
                    Diag.event(ctx, "schedule", "ra khỏi khung giờ → tạm dừng ghi điểm")
                    Prefs.setDesired(ctx, false)
                    goStationary()
                }
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    /** Hẹn giờ tự dừng theo `stopAfterElapsedMinutes`. */
    private val stopAfterRunnable = Runnable {
        stopTracking(userInitiated = false, reason = "hết stopAfterElapsedMinutes")
    }

    /**
     * Bộ lọc giữ mẫu mới nhất lại để nhìn trước. Nếu máy đứng yên, không có mẫu kế tiếp
     * để phán → mẫu đó sẽ nằm chờ mãi. Hẹn giờ này xả nó ra sau [FLUSH_AFTER_MS] không có mẫu mới.
     */
    private val flushRunnable = Runnable { flushPending() }

    /**
     * Nhịp tim: cứ mỗi [HEARTBEAT_MS] ghi 1 dòng vào nhật ký.
     * Nhờ vậy đọc log biết được service có sống liên tục qua nhiều giờ hay bị hệ thống giết.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            Diag.event(
                this@TrackingService, "alive",
                String.format(
                    java.util.Locale.US,
                    "bat=%d%% temp=%.1f°C charging=%s thermal=%s pending=%d total=%d odom=%.0fm moving=%s act=%s %s",
                    level, lastBatteryTemp, charging.toString(), thermalThrottled.toString(),
                    db.count(), db.total(),
                    Prefs.odometer(this@TrackingService),
                    Prefs.isMoving(this@TrackingService), Prefs.activity(this@TrackingService),
                    // Số vệ tinh: chuỗi theo thời gian của `sat=` cho biết máy có "nhìn thấy trời"
                    // hay chỉ đang đoán qua WiFi/cell — xem GnssWatcher.
                    GnssWatcher.summary()
                )
            )
            // Cầu dao an toàn: pin quá yếu thì dừng ghi để máy khỏi chết nguồn
            // (chết nguồn giữa chừng sẽ mất luôn phần log chưa kịp gửi).
            if (!charging && level in 1 until LOW_BATTERY_CUTOFF) {
                this@TrackingService.stopTracking(userInitiated = false, reason = "PIN YẾU ($level%) — bảo vệ máy")
                return
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Diag.attach(this)
        // Theo dõi số vệ tinh: để trả lời được "không thấy vệ tinh" hay "thấy mà không ra vị trí".
        GnssWatcher.start(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        // Sticky broadcast: nhận ngay nhiệt độ hiện tại, sau đó mỗi khi pin đổi.
        runCatching {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()?.let { sticky ->
            lastBatteryTemp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking(userInitiated = true)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                // đứng yên → tắt GPS nhưng GIỮ service sống (xem Prefs.KEY_STATIONARY)
                goStationary()
                return START_STICKY
            }
            ACTION_SYNC -> {
                startForegroundIfNeeded()
                HttpUploader.sync(this)
                return START_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startForegroundIfNeeded() {
        val n = buildNotification(Prefs.config(this).optString("notificationText", "Đang theo dõi vị trí…"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /**
     * Đứng yên: tắt GPS để tiết kiệm pin nhưng KHÔNG tắt service.
     *
     * Vì sao không tắt hẳn? Đã đo thực tế: khi service chết, tiến trình bị hệ thống thu hồi và
     * đăng ký Activity Recognition mất luôn → sáng hôm sau đi cả quãng đường mà không có
     * transition nào báo về, service không tự bật lại. Giữ service sống (không GPS, không
     * wake lock) tốn rất ít pin mà đăng ký cảm biến vẫn nguyên vẹn.
     */
    private fun goStationary() {
        // CHẾ ĐỘ KHÔNG MẤT ĐIỂM: GPS chạy liên tục, không bao giờ tắt vì đứng yên.
        //
        // Đánh đổi: pin hao hơn (GNSS bật liên tục). Nhưng tuyệt đối không có khoảng
        // trắng nào — kể cả 10–30 giây chờ cảm biến đánh thức ở chế độ tiết kiệm.
        // Bật/tắt bằng config `zeroLossMode` (lab có công tắc trong tab Pin).
        if (Prefs.config(this).optBoolean("zeroLossMode", false)) {
            return
        }
        startForegroundIfNeeded()
        // bộ lọc đang giữ một mẫu — ghi nốt trước khi tắt GPS
        flushPending()
        runCatching { fused.removeLocationUpdates(locationCallback) }
        tracking = false
        releaseWakeLock()
        Prefs.setEnabled(this, true)
        Prefs.setStationary(this, true)
        // Đứng yên thật (đã chờ hết stopTimeout) → is_moving=false, và gắn cảm biến
        // significant-motion để lần đi tiếp theo được đánh thức trong vài giây thay vì
        // chờ Activity Recognition (đã đo: trễ tới 5 phút, mất 1,5 km đầu chuyến).
        MovementTracker.reset(this)
        SignificantMotion.start(this)
        scheduleInsideWatchdog()
        Diag.event(
            this, "service",
            "TẠM NGHỈ vì đứng yên — tắt GPS, GIỮ service sống để không mất đăng ký cảm biến"
        )
        val insideCount = Prefs.insideFences(this).size
        updateNotificationText(
            stateText(
                if (insideCount > 0) "Trong vùng geofence — chưa ghi điểm"
                else "Đang đứng yên — tiết kiệm pin"
            )
        )
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        Events.emit(applicationContext, Events.ENABLED, "{\"enabled\":true,\"stationary\":true}")
    }

    /**
     * Hẹn kiểm tra lại vùng sau 10 phút khi GPS đang nghỉ trong geofence.
     * Chỉ chạy khi chế độ bật và thật sự đang ở trong ít nhất một vùng.
     */
    private fun scheduleInsideWatchdog() {
        handler.removeCallbacks(insideWatchdogRunnable)
        if (!Prefs.config(this).optBoolean("pauseInsideGeofence", false)) return
        if (Prefs.insideFences(this).isEmpty()) return
        Diag.log("geofence watchdog: hẹn kiểm tra lại vùng sau 10 phút")
        handler.postDelayed(insideWatchdogRunnable, 10 * 60_000L)
    }

    private fun checkInsideWatchdog() {
        if (!Prefs.desired(this) || Prefs.insideFences(this).isEmpty()) return
        runCatching {
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc == null) {
                        scheduleInsideWatchdog()
                        return@addOnSuccessListener
                    }
                    val inside = Geofences.insideIds(this, loc.latitude, loc.longitude)
                    Prefs.setInsideFences(this, inside)
                    if (inside.isEmpty()) {
                        Diag.event(
                            this, "geofence",
                            "watchdog: đã ra khỏi vùng → bật GPS ghi tiếp"
                        )
                        startTracking()
                    } else {
                        Diag.log(
                            "geofence watchdog: vẫn trong ${inside.size} vùng — kiểm tra lại sau 10 phút"
                        )
                        scheduleInsideWatchdog()
                    }
                }
                .addOnFailureListener { scheduleInsideWatchdog() }
        }.onFailure { scheduleInsideWatchdog() }
    }

    /**
     * WATCHDOG "NHÀ CUNG CẤP VỊ TRÍ IM LẶNG" — chạy mỗi [PROVIDER_WATCHDOG_TICK_MS].
     *
     * Bối cảnh (đo thật 23/10, SM-A127F): request vị trí đang ACTIVE, service sống, mạng tốt
     * mà FusedLocationProvider trả về 0 mẫu suốt 10 phút ⇒ mất 4,8 km hành trình. Trước đây
     * không có gì phát hiện; phải cắm cáp đọc file log mới biết.
     *
     * Hai việc watchdog làm khi phát hiện im lặng:
     *   1. Ghi `Diag.event("gap", …)` → lên thẳng server, xem được ở /diag mà KHÔNG cần cắp log.
     *   2. "ĐÁ" lại provider: đăng ký lại request + một mẫu một-lần `getCurrentLocation`
     *      (nhiều máy sau khi bị kẹt chỉ cần một cú gọi là chạy lại bình thường).
     *
     * CHỈ đá khi app đang CHO LÀ ĐANG DI CHUYỂN ([Prefs.isMoving]) — nếu đứng yên thì không có
     * mẫu là đúng thiết kế (GPS đã tắt theo stopTimeout / đang trong vùng geofence), đá vào
     * chỉ tốn pin. Đây cũng đúng là ca sự cố: `alive` lúc đó báo `moving=true`.
     */
    private val providerWatchdog = object : Runnable {
        override fun run() {
            runCatching { checkProviderSilence() }
                .onFailure { Diag.log("providerWatchdog lỗi: ${it.message}") }
            handler.postDelayed(this, PROVIDER_WATCHDOG_TICK_MS)
        }
    }

    private fun checkProviderSilence() {
        val cfg = Prefs.config(this)
        val minutes = cfg.optDouble("providerSilenceMinutes", 3.0)
        if (minutes <= 0.0) return          // 0 = tắt watchdog
        if (!tracking) return               // GPS đang tắt (đã tạm nghỉ theo stopTimeout) thì bình thường
        val now = SystemClock.elapsedRealtime()
        if (lastProviderSampleAt == 0L) { lastProviderSampleAt = now; return }
        val silenceMs = now - lastProviderSampleAt

        // CẢNH BÁO TỪ THỰC TẾ 23/10 (SM-A127F): máy DỪNG XE Ở ĐÈN ĐỎ rồi mất hẳn 10 phút hành
        // trình (4,8 km). Vì vậy KHÔNG được chỉ đá khi `moving == true`:
        //   • đang đi        → im 3 phút là bất thường  → đá theo `providerSilenceMinutes`.
        //   • trông như đứng → im lâu là chuyện thường (GPS không cần fix dày) → chỉ đá khi im
        //                      QUÁ lâu (gấp 3 lần). Nhưng VẪN PHẢI ĐÁ, vì `tracking == true`
        //                      nghĩa là ta đã xin nhận vị trí liên tục mà 9 phút không có gì.
        val act = Prefs.activity(this)
        val movingHint = Prefs.isMoving(this) ||
            act == "in_vehicle" || act == "on_bicycle" ||
            act == "walking" || act == "on_foot" || act == "running"
        // KHÔNG đá khi đang ở TRONG một vùng geofence.
        //
        // Đo thật 23/10: SM-A066B đỗ xe ở nhà → không có mẫu nào (đứng yên thì không cần fix dày)
        // nhưng `isMoving` còn true nên watchdog đã ĐÁ OAN 3 lần (gap 165–281 s, cách nhau 4–7 m).
        // Ở trong vùng thì việc đứng yên không có mẫu là ĐÚNG, và khi rời vùng thì geofence EXIT
        // của hệ điều hành sẽ đánh thức app (cộng thêm watchdog-trong-vùng 10 phút làm dự phòng)
        // → không cần tự đá ở đây.
        if (Prefs.insideFences(this).isNotEmpty()) return
        val thresholdMin = if (movingHint) minutes else minutes * 3
        if (silenceMs < (thresholdMin * 60_000).toLong()) return
        if (lastProviderKickAt != 0L && now - lastProviderKickAt < PROVIDER_KICK_COOLDOWN_MS) return

        lastProviderKickAt = now
        lastProviderSampleAt = now          // hẹn lại từ mốc này, tránh đá liên tục
        providerKicks++
        val mins = silenceMs / 60_000
        Diag.event(
            this, "gap",
            "KHÔNG có mẫu vị trí trong $mins phút (tracking=true, " +
                (if (movingHint) "đang di chuyển" else "trông như đứng yên, act=$act") +
                ") → đá lại provider (lần $providerKicks) | ${GnssWatcher.summary()}"
        )

        // (a) Đăng ký lại request vị trí.
        runCatching { fused.removeLocationUpdates(locationCallback) }
        runCatching {
            fused.requestLocationUpdates(buildRequest(cfg, 0.0), locationCallback, mainLooper)
        }
        // (b) Một mẫu một-lần để ép provider trả về ngay — cho vào đúng đường onLocation()
        //     để bộ lọc / odometer / geofence xử lý như mẫu bình thường.
        runCatching {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc == null) {
                        Diag.log("watchdog: getCurrentLocation trả về null")
                    } else {
                        Diag.log(String.format(
                            java.util.Locale.US,
                            "watchdog: getCurrentLocation → %.5f,%.5f acc=%.0f",
                            loc.latitude, loc.longitude, loc.accuracy
                        ))
                        onLocation(loc)
                    }
                }
                .addOnFailureListener { e -> Diag.log("watchdog: getCurrentLocation lỗi: ${e.message}") }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun startTracking() {
        val cfg = Prefs.config(this)
        startForegroundIfNeeded()
        Prefs.setDesired(this, true)

        // Đã đăng ký GPS rồi thì không đăng ký lại. Chỉ hẹn lại stop-detection: đây chính là
        // đường app khởi động lại lúc đang đứng yên (sự kiện STILL không bắn lại vì trạng thái
        // đã là still từ trước, nên phải tự hẹn tắt GPS theo stopTimeout).
        if (tracking) {
            val key = locationRequestKey(cfg)
            if (key != requestKey) {
                // Cấu hình đổi trong lúc đang chạy → đăng ký lại GPS với tham số mới.
                // Trước đây setConfig() gọi start() nhưng bị chặn ở đây nên thay đổi
                // distanceFilter/interval không có tác dụng cho tới khi stop()/start().
                runCatching { fused.removeLocationUpdates(locationCallback) }
                filterFor(cfg).reset()
                elasticBucket = -1
                try {
                    fused.requestLocationUpdates(buildRequest(cfg, 0.0), locationCallback, mainLooper)
                } catch (e: SecurityException) {
                    stopSelf()
                    return
                }
                requestKey = key
                Diag.event(
                    this, "service",
                    "cấu hình đổi — đăng ký lại GPS: interval=${cfg.optLong("interval", 5000L)}ms " +
                        "distanceFilter=${cfg.optDouble("distanceFilter", 10.0)}m " +
                        "accuracy=${cfg.optString("desiredAccuracy", "high")}"
                )
            }
            if (cfg.optBoolean("motionDetection", true) && Prefs.activity(this) == "still") {
                StopDetector.onStill(this)
            }
            return
        }

        val wasStationary = Prefs.stationary(this)
        Prefs.setStationary(this, false)
        // đang có hẹn tắt GPS vì đứng yên thì huỷ — nay đã chạy lại
        StopDetector.onMoving(this)
        // Nếu hoạt động gần nhất là đứng yên thì hẹn tắt GPS theo stopTimeout. Không có bước
        // này, app khởi động lại lúc đang đứng yên sẽ giữ GPS chạy mãi vì sự kiện STILL
        // không bắn lại (trạng thái đã là still từ trước).
        if (cfg.optBoolean("motionDetection", true) && Prefs.activity(this) == "still") {
            StopDetector.onStill(this)
        }
        Diag.event(
            this, "service",
            (if (wasStationary) "CỬ ĐỘNG trở lại — bật GPS. " else "") +
                "BẮT ĐẦU theo dõi interval=${cfg.optLong("interval", 5000L)}ms " +
                "distanceFilter=${cfg.optDouble("distanceFilter", 10.0)}m " +
                "accuracy=${cfg.optString("desiredAccuracy", "high")} " +
                "motionDetection=${cfg.optBoolean("motionDetection", true)} " +
                "zeroLoss=${cfg.optBoolean("zeroLossMode", false)} " +
                // Cho biết watchdog "provider im lặng" có bật không — đọc log/server là kiểm được.
                "providerSilence=${cfg.optDouble("providerSilenceMinutes", 3.0)}m " +
                "url=${cfg.optString("url")}"
        )

        val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rnbggeo:tracking")
        wl.setReferenceCounted(false)
        runCatching { wl.acquire() }
        wakeLock = wl

        // GPS chuẩn bị chạy → không cần cảm biến đánh thức nữa
        SignificantMotion.stop(this)

        // cấu hình đổi thì bộ lọc phải dựng lại (ngưỡng, Kalman, policy…)
        filterFor(cfg).reset()
        // Mốc di chuyển cũng phải mới: mốc cũ từ trước lúc tạm nghỉ có thể cách đây hàng km
        MovementTracker.reset(this)
        // Bắt đầu phiên GPS mới → đếm lại thời gian ở trong vùng geofence
        handler.removeCallbacks(insideWatchdogRunnable)
        insideSinceMs = 0L
        elasticBucket = -1

        val request = buildRequest(cfg, 0.0)

        try {
            fused.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            stopSelf()
            return
        }
        tracking = true
        requestKey = locationRequestKey(cfg)
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        handler.removeCallbacks(heartbeatJs)
        handler.post(heartbeatJs)
        handler.removeCallbacks(scheduleTick)
        handler.post(scheduleTick)
        // Watchdog "provider im lặng": bắt đầu tính từ lúc này, không đá oan ngay sau khi bật.
        lastProviderSampleAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(providerWatchdog)
        handler.postDelayed(providerWatchdog, PROVIDER_WATCHDOG_TICK_MS)
        scheduleStopAfter(cfg)
        Prefs.setEnabled(this, true)
        Events.emit(applicationContext, Events.ENABLED, "{\"enabled\":true}")
    }

    /** `stopAfterElapsedMinutes` — tự gọi stop() sau N phút kể từ lúc start. */
    private fun scheduleStopAfter(cfg: JSONObject) {
        handler.removeCallbacks(stopAfterRunnable)
        val minutes = cfg.optInt("stopAfterElapsedMinutes", 0)
        if (minutes > 0) {
            Diag.log("hẹn tự dừng sau $minutes phút")
            handler.postDelayed(stopAfterRunnable, minutes * 60_000L)
        }
    }

    /** Chỉ các khoá ảnh hưởng tới LocationRequest — đổi mấy khoá này mới cần đăng ký lại GPS. */
    private fun locationRequestKey(cfg: JSONObject): String = buildString {
        append(cfg.optLong("interval", 5000L)).append('|')
        append(cfg.optDouble("distanceFilter", 10.0)).append('|')
        append(cfg.optString("desiredAccuracy", "high")).append('|')
        append(cfg.optBoolean("useSignificantChangesOnly", false)).append('|')
        append(cfg.optLong("locationUpdateInterval", 1000L)).append('|')
        append(cfg.optLong("fastestLocationUpdateInterval", -1L)).append('|')
        append(cfg.optLong("deferTime", 0L)).append('|')
        append(cfg.optBoolean("disableElasticity", false)).append('|')
        append(cfg.optDouble("elasticityMultiplier", 1.0))
    }

    private fun accuracyPriority(name: String): Int = when (name) {
        "low" -> Priority.PRIORITY_LOW_POWER
        "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        "passive" -> Priority.PRIORITY_PASSIVE
        else -> Priority.PRIORITY_HIGH_ACCURACY
    }

    /**
     * `distanceFilter` sau khi áp elasticity.
     *
     * Công thức của transistorsoft: `(round(speed, 5) / 5) × distanceFilter × elasticityMultiplier`
     * — tức hệ số nhân chính là tốc độ đã làm tròn về bội số 5 m/s. Đi bộ (dưới 2.5 m/s)
     * cho hệ số 0, nên phải chặn dưới bằng 1 để vẫn giữ distanceFilter gốc.
     */
    private fun elasticDistance(cfg: JSONObject, speed: Double): Float {
        val base = cfg.optDouble("distanceFilter", 10.0)
        if (cfg.optBoolean("disableElasticity", false)) return base.toFloat()
        val mult = cfg.optDouble("elasticityMultiplier", 1.0)
        if (mult <= 0.0) return base.toFloat()
        val factor = Math.round(speed / 5.0).coerceAtLeast(1).toDouble()
        return (base * factor * mult).toFloat()
    }

    private fun buildRequest(cfg: JSONObject, speed: Double): LocationRequest {
        val interval = cfg.optLong("interval", 5000L)
        var distance = elasticDistance(cfg, speed)

        // `useSignificantChangesOnly`: iOS có API significant-change riêng, Android không có.
        // Nên ở đây cài bằng chế độ tương đương về mặt tiêu pin: ưu tiên cân bằng năng lượng,
        // chỉ nhận khi đi xa ≥ 500 m và không dày hơn 60 giây một mẫu. Đủ để vẽ hành trình
        // dài ngày mà không rút cạn pin — không phải bản sao hành vi của iOS.
        var priority = accuracyPriority(cfg.optString("desiredAccuracy", "high"))
        var intervalMs = interval
        if (cfg.optBoolean("useSignificantChangesOnly", false)) {
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
            if (distance < 500f) distance = 500f
            if (intervalMs < 60_000L) intervalMs = 60_000L
        }
        // Nóng máy: giảm tải nhưng vẫn ghi đều.
        if (thermalThrottled) {
            priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
            if (distance < 50f) distance = 50f
            if (intervalMs < 30_000L) intervalMs = 30_000L
        }

        val b = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateDistanceMeters(distance)
            .setWaitForAccurateLocation(false)

        // Giống transistorsoft: locationUpdateInterval chỉ có hiệu lực khi distanceFilter = 0
        if (distance <= 0f) {
            val lui = cfg.optLong("locationUpdateInterval", 1000L)
            if (lui > 0) b.setIntervalMillis(lui)
            val fastest = cfg.optLong("fastestLocationUpdateInterval", -1L)
            if (fastest > 0) b.setMinUpdateIntervalMillis(fastest)
        }
        val defer = cfg.optLong("deferTime", 0L)
        if (defer > 0) b.setMaxUpdateDelayMillis(defer)
        return b.build()
    }

    /** Dựng lại bộ lọc khi cấu hình thay đổi; giữ nguyên instance nếu không đổi. */
    private fun filterFor(cfg: JSONObject): LocationFilter {
        filter?.let { if (filterVersion == Prefs.configVersion) return it }
        val nf = LocationFilter(cfg)
        filter = nf
        filterVersion = Prefs.configVersion
        Diag.log("bộ lọc: ${nf.summary()}")
        return nf
    }

    /** Đẩy nốt mẫu bộ lọc đang giữ (nếu có) — gọi trước khi tắt GPS hoặc dừng service. */
    private fun flushPending() {
        handler.removeCallbacks(flushRunnable)
        val cfg = Prefs.config(this)
        val f = filterFor(cfg)
        runCatching { f.flush()?.let { commit(cfg, it, f) } }
            .onFailure { Diag.log("flushPending lỗi: ${it.message}") }
    }

    private fun stopTracking(userInitiated: Boolean, reason: String = "") {
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(heartbeatJs)
        handler.removeCallbacks(stopAfterRunnable)
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(insideWatchdogRunnable)
        handler.removeCallbacks(providerWatchdog)
        StopDetector.onMoving(this)
        SignificantMotion.stop(this)
        MovementTracker.reset(this)
        // ghi nốt mẫu bộ lọc đang giữ trước khi dừng
        flushPending()
        runCatching { fused.removeLocationUpdates(locationCallback) }
        tracking = false
        releaseWakeLock()
        Prefs.setEnabled(this, false)
        Prefs.setStationary(this, false)
        if (userInitiated) Prefs.setDesired(this, false)
        Diag.event(
            this, "service",
            when {
                userInitiated -> "DỪNG theo dõi (người dùng bấm)"
                reason.isNotEmpty() -> "DỪNG theo dõi — $reason"
                else -> "DỪNG theo dõi — tắt GPS"
            }
        )
        Events.emit(applicationContext, Events.ENABLED, "{\"enabled\":false}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun onLocation(raw: Location) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        // Watchdog "provider im lặng" đếm lại từ đây: provider CÓ giao mẫu, kể cả khi sau đó
        // bộ lọc bỏ mẫu (đứng yên / trong vùng geofence). Chỉ khi KHÔNG có mẫu nào mới đáng lo.
        lastProviderSampleAt = SystemClock.elapsedRealtime()
        lastSampleSpeed = if (raw.hasSpeed()) raw.speed else 0f
        try {
            val cfg = Prefs.config(this)
            // Bộ lọc giữ mẫu mới nhất lại một nhịp để phát hiện gai một điểm;
            // `null` nghĩa là chưa có gì để ghi ở lần này.
            val f = filterFor(cfg)
            val res = f.feed(raw)

            // mẫu có thể còn nằm chờ trong bộ lọc → hẹn xả nếu không có mẫu kế tiếp
            handler.removeCallbacks(flushRunnable)
            handler.postDelayed(flushRunnable, FLUSH_AFTER_MS)

            if (res != null) commit(cfg, res, f)
        } finally {
            recordPerf(SystemClock.elapsedRealtimeNanos() - t0)
        }
    }

    /**
     * Đo chi phí xử lý MỘT mẫu trên main thread (lọc + ghi DB + notification + lên lịch sync).
     * Log mỗi 100 mẫu để biết máy có bị nóng vì code hay không; không log từng mẫu.
     */
    private fun recordPerf(ns: Long) {
        perfSamples++
        perfTotalNs += ns
        if (ns > perfMaxNs) perfMaxNs = ns
        if (perfSamples < 100) return
        Diag.log(
            String.format(
                java.util.Locale.US,
                "perf: %d mẫu, TB %.2f ms/mẫu, max %.2f ms",
                perfSamples, perfTotalNs / 1_000_000.0 / perfSamples, perfMaxNs / 1_000_000.0
            )
        )
        perfSamples = 0
        perfTotalNs = 0L
        perfMaxNs = 0L
    }

    /** Ghi một mẫu đã được bộ lọc phán quyết (nhận hoặc loại). */
    private fun commit(cfg: JSONObject, res: LocationFilter.Result, f: LocationFilter) {
        val persist = cfg.optBoolean("persist", true)

        if (!res.accepted) {
            val raw = res.raw
            if (raw != null) {
                Diag.log(
                    String.format(
                        java.util.Locale.US,
                        "loc BỎ (%s) %.5f,%.5f acc=%.1f dist=%.1fm | tổng đã bỏ %d",
                        res.reason, raw.latitude, raw.longitude, raw.accuracy,
                        res.rawDistance, f.rejected
                    )
                )
                emitFilterEvent(raw, res.reason, res.rawDistance, f)
            }
            return
        }

        val location = res.location ?: return
        val raw = res.raw ?: location

        // đang chờ tắt GPS mà đã đi quá stationaryRadius → Activity Recognition báo sai, huỷ hẹn
        StopDetector.onLocation(this, location.latitude, location.longitude)

        // ---------- 1b. Xác nhận di chuyển bằng GPS ----------
        // Activity Recognition chỉ dùng để GỢI Ý bật GPS. "Đang di chuyển" phải do GPS
        // chứng minh (chuỗi mẫu + quãng dịch chuyển thật). Trước đây một mẫu tốc độ
        // 1,1 m/s là đủ, nên máy nằm yên trong xe vẫn hiện "ĐANG DI CHUYỂN" mãi và
        // huỷ luôn hẹn tắt GPS sau stopTimeout.
        val moving = MovementTracker.onLocation(
            this,
            location.latitude,
            location.longitude,
            location.speed,
            location.hasSpeed()
        )

        // ---------- 1b2. Chế độ "không ghi điểm khi ĐỨNG YÊN trong vùng geofence" ----------
        // Tương đương tinh thần startGeofences() của lib gốc: đứng yên trong vùng thì chỉ
        // theo dõi geofence, KHÔNG ghi vị trí. GPS trôi trong văn phòng vì vậy không
        // sinh ra chùm điểm và đoạn xám ngoằn ngoèo trên bản đồ.
        //
        // QUAN TRỌNG — chỉ bỏ mẫu khi ĐANG ĐỨNG YÊN. Trước đây bỏ mọi mẫu ở trong vùng,
        // nên toàn bộ đoạn ~200 m cuối lúc về và đoạn đầu lúc rời nhà/công ty bị mất
        // (bằng chứng: 19/10 17:05→17:10 và 20/10 13:22→13:25). Đang di chuyển thì
        // vẫn ghi, để tuyến đường không bị đứt ở hai đầu.
        //
        // Dự phòng 3 lớp khi ra ngoài:
        //   1. EXIT của geofence OS → GeofenceReceiver gọi start().
        //   2. significant-motion → bật GPS kiểm tra.
        //   3. Mẫu GPS đầu tiên ở ngoài tự kết luận qua Geofences.insideIds().
        // Tách bạch 2 công tắc:
        //   • pauseInsideGeofence = có GHI ĐIỂM khi đứng yên trong vùng hay không.
        //   • zeroLossMode        = có TẮT GPS khi đứng yên hay không.
        // Đang bật "không mất điểm" thì khối này vẫn chạy (vẫn không ghi điểm trong vùng
        // nếu đứng yên), chỉ có goStationary() là không tắt GPS mà thôi.
        if (cfg.optBoolean("pauseInsideGeofence", false)) {
            val inside = Geofences.insideIds(this, location.latitude, location.longitude)
            if (Prefs.insideFences(this) != inside) Prefs.setInsideFences(this, inside)

            if (inside.isNotEmpty()) {
                if (insideSinceMs == 0L) {
                    insideSinceMs = System.currentTimeMillis()
                    Diag.event(
                        this, "geofence",
                        "vào vùng (${inside.size} vùng) — không ghi điểm khi đứng yên"
                    )
                }

                // Đang di chuyển trong vùng → ghi điểm bình thường (rơi xuống dưới).
                // Nhờ vậy đoạn rời vùng và đoạn vào vùng vẫn liền mạch. Chỉ bỏ mẫu khi
                // chắc chắn đứng yên: MovementTracker chưa xác nhận đi, không phải đi bộ,
                // và tốc độ mẫu gần nhất vẫn ≤ 0,7 m/s.
                if (!moving && !walkingHint() && lastSampleSpeed <= 0.7f) {
                    val bmIn = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val levelIn = bmIn.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100f
                    val chargingIn = bmIn.isCharging
                    val mockIn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        location.isMock
                    } else {
                        location.isFromMockProvider
                    }
                    val jsonIn = Util.toJson(
                        location = location,
                        activityType = Prefs.activity(this),
                        activityConfidence = 100,
                        isMoving = moving,
                        event = "location",
                        batteryLevel = levelIn,
                        isCharging = chargingIn,
                        odometer = Prefs.odometer(this),
                        mock = mockIn
                    )
                    // Vẫn cập nhật vị trí cuối + chấm geofence để bắt EXIT, nhưng KHÔNG ghi DB.
                    Prefs.saveLastLocation(this, jsonIn)
                    Prefs.setLastFixTime(this, location.time)
                    Geofences.syncNearby(this, location.latitude, location.longitude)
                    Polygons.evaluate(
                        this, location.latitude, location.longitude,
                        System.currentTimeMillis(), jsonIn
                    )
                    Diag.log(
                        String.format(
                            java.util.Locale.US,
                            "loc BỎ (đứng yên trong geofence, không ghi điểm) %.5f,%.5f spd=%.2f",
                            location.latitude, location.longitude, location.speed
                        )
                    )
                    emitFilterEvent(raw, "geofence", 0.0, f)
                    // Không gọi COUNT(*) mỗi mẫu bị bỏ: nhánh này chạy 5 giây/lần.
                    if (SystemClock.elapsedRealtime() - lastNotifAt > 10_000L) {
                        updateNotificationText(stateText("Trong vùng geofence — chưa ghi điểm"))
                    }
                    return
                }

                // Đang đi trong vùng: vẫn ghi điểm bình thường. Việc tắt GPS khi đứng yên
                // do StopDetector (stopTimeout) lo, giống lib gốc.
                Diag.log(
                    String.format(
                        java.util.Locale.US,
                        "đang di chuyển trong geofence → vẫn ghi điểm %.5f,%.5f",
                        location.latitude, location.longitude
                    )
                )
            } else if (insideSinceMs != 0L) {
                Diag.event(this, "geofence", "đã ra khỏi vùng — ghi điểm trở lại")
                insideSinceMs = 0L
            }
        }

        // ---------- 1c. Chặn GPS trôi khi đứng yên ----------
        // Nằm yên, GPS vẫn trôi vài mét mỗi mẫu; distanceFilter 10 m không đủ chặn.
        // Đã đo trên dữ liệu thật: 184 điểm trong 53 m suốt 50 phút → vẽ thành đám rối.
        // Khi đứng yên, chỉ ghi khi đã rời mốc cũ quá ngưỡng.
        if (cfg.optBoolean("motionDetection", true) && !moving) {
            val prev = Prefs.lastLocation(this)?.optJSONObject("coords")
            if (prev != null) {
                val d = Util.haversine(
                    prev.optDouble("latitude"), prev.optDouble("longitude"),
                    location.latitude, location.longitude
                )
                val minMove = cfg.optJSONObject("filter")
                    ?.optDouble("stationaryDistanceThreshold", 30.0) ?: 30.0
                if (d < minMove) {
                    Diag.log(
                        String.format(
                            java.util.Locale.US,
                            "loc BỎ (đứng yên, lệch %.0fm < %.0fm) %.5f,%.5f",
                            d, minMove, location.latitude, location.longitude
                        )
                    )
                    emitFilterEvent(raw, "stationary", d, f)
                    return
                }
            }
        }

        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100f
        val charging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bm.isCharging
        } else {
            val st = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }

        // ---------- 2. odometer ----------
        // Bộ lọc đã lo mẫu xấu; ở đây chỉ còn luật "đứt quãng thì không cộng".
        //
        // So giờ-FIX với giờ-FIX (Location.time), KHÔNG so với `last_location.timestamp`:
        // trường đó là giờ đồng hồ lúc ghi, mà bộ lọc giữ mẫu lại một nhịp nên giờ ghi
        // luôn trễ hơn giờ fix ~5 s → hiệu số ra ÂM → odometer không bao giờ cộng
        // (lỗi thấy rõ: 19/10 17:15→20/10 13:29 odometer đứng im ở 7723,63 m dù đã đi ~6 km).
        val last = Prefs.lastLocation(this)
        val lastFix = Prefs.lastFixTime(this)
        val gapMs = if (last != null && lastFix > 0L) raw.time - lastFix else 0L
        val continuous = last != null && gapMs in -MAX_CLOCK_SKEW_MS..MAX_ODOMETER_GAP_MS
        if (!continuous && last != null && gapMs > MAX_ODOMETER_GAP_MS) {
            Diag.log("bỏ qua odometer: cách điểm trước ${gapMs / 1000}s (>${MAX_ODOMETER_GAP_MS / 1000}s)")
        }
        if (continuous && res.odometerDelta > 0) Prefs.addOdometer(this, res.odometerDelta)

        val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            location.isFromMockProvider
        }

        val json = Util.tagDevice(
            Util.toJson(
                location = location,
                activityType = Prefs.activity(this),
                activityConfidence = 100,
                isMoving = moving,
                event = "location",
                batteryLevel = level,
                isCharging = charging,
                odometer = Prefs.odometer(this),
                mock = mock
            ),
            this
        )

        if (persist) db.insert(json)
        Prefs.saveLastLocation(this, json)
        Prefs.setLastFixTime(this, raw.time)

        // ghi lại từng điểm vào nhật ký file — dùng Locale.US để dấu thập phân luôn là '.'
        Diag.log(
            String.format(
                java.util.Locale.US,
                "loc %.5f,%.5f spd=%.2f acc=%.1f hdg=%.0f alt=%.0f act=%s bat=%d odom=%.0f mock=%s%s",
                location.latitude, location.longitude, location.speed, location.accuracy,
                location.bearing, location.altitude, Prefs.activity(this),
                (level * 100).toInt(), Prefs.odometer(this), mock.toString(),
                if (res.smoothed) " [kalman]" else ""
            )
        )

        Events.emit(applicationContext, Events.LOCATION, json.toString())
        updateNotification(json)

        // ---------- 3. elasticity: đổi bậc tốc độ thì đổi distanceFilter ----------
        applyElasticity(cfg, location.speed.toDouble())

        // ---------- 4. geofence theo bán kính (chỉ nạp vùng lân cận) ----------
        Geofences.syncNearby(this, location.latitude, location.longitude)

        // ---------- 4b. geofence ĐA GIÁC: chấm điểm trên chính mẫu đã lọc ----------
        Polygons.evaluate(this, location.latitude, location.longitude, System.currentTimeMillis(), json)

        // auto-upload if configured
        if (persist && cfg.optString("url").isNotEmpty() && cfg.optBoolean("autoSync", true)) {
            val threshold = cfg.optInt("autoSyncThreshold", 0)
            // threshold <= 0 nghĩa là gửi mỗi điểm: KHÔNG cần COUNT(*) mỗi mẫu.
            if (threshold <= 0 || db.count() >= threshold) HttpUploader.sync(this)
        }
    }

    /**
     * Elastivity: distanceFilter giãn ra theo tốc độ. Android chốt distanceFilter lúc đăng ký
     * LocationRequest, nên mỗi khi đổi bậc 5 m/s phải đăng ký lại — nhưng chỉ khi bậc ĐỔI,
     * không phải mỗi mẫu.
     */
    private fun applyElasticity(cfg: JSONObject, speed: Double) {
        lastSpeed = speed
        val base = cfg.optDouble("distanceFilter", 10.0)
        if (base <= 0.0) return
        if (cfg.optBoolean("disableElasticity", false)) return
        if (cfg.optDouble("elasticityMultiplier", 1.0) <= 0.0) return

        val bucket = Math.round(speed.coerceAtLeast(0.0) / 5.0).coerceAtLeast(1L).toInt()
        if (bucket == elasticBucket) return
        elasticBucket = bucket
        runCatching {
            fused.requestLocationUpdates(buildRequest(cfg, speed), locationCallback, mainLooper)
            Diag.log(
                String.format(
                    java.util.Locale.US,
                    "elasticity: speed=%.1f m/s (bậc %d) → distanceFilter=%.0fm",
                    speed, bucket, elasticDistance(cfg, speed)
                )
            )
        }.onFailure { Diag.log("elasticity re-request lỗi: ${it.message}") }
    }

    /** Báo cho JS biết một mẫu vừa bị loại — để hiển thị "vì sao mất điểm". */
    private fun emitFilterEvent(raw: Location, reason: String, distance: Double, f: LocationFilter) {
        val o = JSONObject()
        o.put("reason", reason)
        o.put("timestamp", System.currentTimeMillis())
        o.put("distance", distance)
        o.put("rejected", f.rejected)
        o.put("accepted", f.accepted)
        val c = JSONObject()
        c.put("latitude", raw.latitude)
        c.put("longitude", raw.longitude)
        c.put("speed", raw.speed.toDouble())
        c.put("accuracy", raw.accuracy.toDouble())
        o.put("coords", c)
        Events.emit(applicationContext, Events.LOCATION_FILTER, o.toString())
    }

    private fun updateNotification(loc: JSONObject) {
        // Trước đây notification được dựng + notify MỖI mẫu GPS, kèm một COUNT(*).
        // Đây là việc đắt (IPC sang system_server) và không ai cần tới mức 5 giây.
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifAt < NOTIF_MIN_INTERVAL_MS) return
        lastNotifAt = now
        val c = loc.optJSONObject("coords") ?: return
        val prefix = if (thermalThrottled) "Nóng máy • " else ""
        // Mặc định KHÔNG hiện toạ độ: notification đứng yên, không nhấp nháy.
        // Muốn xem toạ độ sống thì đặt config notificationLiveCoords = true.
        if (!Prefs.config(this).optBoolean("notificationLiveCoords", false)) {
            updateNotificationText(stateText(prefix + "Đang ghi hành trình"))
            return
        }
        // 4 số lẻ ≈ 11 m: đủ để nhìn, không đổi liên tục vì GPS trôi vài mét.
        updateNotificationText(
            String.format(
                java.util.Locale.US,
                "%s%.4f, %.4f • %.1f m/s • %d điểm chờ gửi",
                prefix,
                c.optDouble("latitude"), c.optDouble("longitude"), c.optDouble("speed"), db.count()
            )
        )
    }

    /** Text trạng thái + số điểm chờ, dùng chung để 2 nhánh không "giành" notification. */
    private fun stateText(state: String): String =
        String.format(java.util.Locale.US, "%s • %d điểm chờ gửi", state, db.count())

    private fun updateNotificationText(text: String) {
        // Nội dung không đổi thì không gọi NotificationManager.notify lại.
        if (text == lastNotifText) return
        lastNotifText = text
        lastNotifAt = SystemClock.elapsedRealtime()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    /** Áp dụng / bỏ chế độ giảm tải vì nhiệt độ pin. */
    private fun applyThermalMode() {
        val cfg = Prefs.config(this)
        Diag.event(
            this, "thermal",
            if (thermalThrottled) {
                String.format(
                    java.util.Locale.US,
                    "pin %.1f°C ≥ %.1f°C — giảm GPS xuống balanced/30s/50m",
                    lastBatteryTemp, cfg.optDouble("thermalLimitC", 42.0)
                )
            } else {
                String.format(
                    java.util.Locale.US,
                    "pin %.1f°C — đã hạ nhiệt, khôi phục GPS",
                    lastBatteryTemp
                )
            }
        )
        if (!tracking) return
        runCatching {
            fused.requestLocationUpdates(buildRequest(cfg, lastSpeed), locationCallback, mainLooper)
        }.onFailure { Diag.log("thermal re-request lỗi: ${it.message}") }
        updateNotificationText(
            if (thermalThrottled) "Nóng máy — giảm tần suất GPS • đang theo dõi"
            else "Đang theo dõi vị trí…"
        )
    }

    /** Kênh đang dùng theo cấu hình `notificationMinimal` (mặc định: tối giản). */
    private fun channelId(): String {
        val minimal = Prefs.config(this).optBoolean("notificationMinimal", true)
        return if (minimal) CHANNEL_ID_MIN else CHANNEL_ID
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val minimal = Prefs.config(this).optBoolean("notificationMinimal", true)

        // Kênh tối giản: không icon status bar, không âm thanh/rung, không badge.
        val quiet = NotificationChannel(
            CHANNEL_ID_MIN,
            "Theo dõi vị trí (ẩn)",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Thông báo thường trực khi app ghi hành trình — không hiện icon"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        nm.createNotificationChannel(quiet)

        // Kênh chi tiết: giữ lại cho ai muốn thấy icon + nội dung trên status bar.
        val loud = NotificationChannel(
            CHANNEL_ID,
            "Theo dõi vị trí (có icon)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Thông báo khi app đang ghi hành trình"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(loud)

        // Dọn kênh không dùng để Settings không hiện 2 mục trùng tên.
        // Chỉ xoá kênh KHÔNG phải kênh đang phát notification của service.
        runCatching { nm.deleteNotificationChannel(if (minimal) CHANNEL_ID else CHANNEL_ID_MIN) }
    }

    private fun buildNotification(text: String): Notification {
        val cfg = Prefs.config(this)
        val pkg = packageName
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val iconName = cfg.optString("notificationIcon", "")
        val icon = if (iconName.isNotEmpty())
            resources.getIdentifier(iconName, "drawable", pkg)
        else applicationInfo.icon
        return NotificationCompat.Builder(this, channelId())
            .setContentTitle(cfg.optString("notificationTitle", "bgGeoLab đang chạy"))
            .setContentText(text)
            .setSmallIcon(if (icon != 0) icon else android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)   // cập nhật nội dung nhưng KHÔNG báo lại lần nữa
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(heartbeatJs)
        handler.removeCallbacks(scheduleTick)
        handler.removeCallbacks(stopAfterRunnable)
        handler.removeCallbacks(insideWatchdogRunnable)
        handler.removeCallbacks(providerWatchdog)
        runCatching { flushPending() }
        runCatching { fused.removeLocationUpdates(locationCallback) }
        GnssWatcher.stop()
        SignificantMotion.stop(this)
        tracking = false
        releaseWakeLock()
        // service đã chết thì đánh dấu enabled=false để trạng thái không bị cũ
        Prefs.setEnabled(this, false)
        Prefs.setStationary(this, false)
        Diag.event(this, "service", "onDestroy — service bị huỷ")
        super.onDestroy()
    }
}
