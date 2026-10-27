package com.rnbggeo

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Stop-detection có độ trễ — tương đương `stopTimeout` + `stationaryRadius` của transistorsoft.
 *
 * Vì sao phải trễ: Activity Recognition báo `STILL` rất sớm. Nếu tắt GPS ngay lập tức thì
 * mỗi lần xe dừng đèn đỏ, mỗi lần đứng chờ thang máy là một lần mất dấu — quãng đường bị
 * đứt thành nhiều khúc. Vì vậy SDK gốc chờ thêm `stopTimeout` phút mới thật sự tắt.
 *
 * Trong lúc chờ, GPS vẫn chạy nên còn một lớp kiểm tra thứ hai: nếu máy đã rời xa điểm neo
 * quá `stationaryRadius` thì Activity Recognition đã báo sai → huỷ hẹn, giữ GPS.
 */
object StopDetector {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** Điểm neo lúc bắt đầu đứng yên — mốc để kiểm tra `stationaryRadius`. */
    private var anchorLat = Double.NaN
    private var anchorLng = Double.NaN

    @Volatile
    var waiting: Boolean = false
        private set

    private fun actCfg(cfg: JSONObject): JSONObject = cfg.optJSONObject("activity") ?: JSONObject()

    /** Tắt hẳn cơ chế phát hiện dừng (GPS chạy mãi). */
    fun stopDetectionDisabled(cfg: JSONObject): Boolean =
        actCfg(cfg).optBoolean("disableStopDetection", false) ||
            cfg.optBoolean("disableStopDetection", false)

    /** Đứng yên thì gọi `stop()` hẳn thay vì chỉ tạm nghỉ. */
    private fun stopOnStationary(cfg: JSONObject): Boolean =
        actCfg(cfg).optBoolean("stopOnStationary", false) ||
            cfg.optBoolean("stopOnStationary", false)

    fun onStill(ctx: Context) {
        val cfg = Prefs.config(ctx)
        // Chế độ KHÔNG MẤT ĐIỂM: không hẹn tắt GPS dù đứng yên bao lâu.
        if (cfg.optBoolean("zeroLossMode", false)) return
        if (stopDetectionDisabled(cfg)) {
            Diag.log("stop-detection TẮT — bỏ qua STILL, giữ GPS chạy")
            return
        }
        // Đang chờ tắt rồi thì GIỮ NGUYÊN hẹn. Nếu reset mỗi lần nhận STILL (activity
        // hoặc tốc độ GPS thấp), bộ đếm stopTimeout sẽ không bao giờ chạy hết.
        if (waiting) return
        cancel()

        val anchor = Prefs.lastLocation(ctx)?.optJSONObject("coords")
        anchorLat = anchor?.optDouble("latitude") ?: Double.NaN
        anchorLng = anchor?.optDouble("longitude") ?: Double.NaN

        val minutes = cfg.optDouble("stopTimeout", 5.0)
        if (minutes <= 0.0) {
            Diag.event(ctx, "motion", "STILL và stopTimeout=0 → tắt GPS ngay")
            fire(ctx)
            return
        }

        waiting = true
        val r = Runnable { fire(ctx) }
        pending = r
        handler.postDelayed(r, (minutes * 60_000).toLong())
        Diag.event(
            ctx, "motion",
            String.format(java.util.Locale.US, "STILL → chờ %.1f phút rồi mới tắt GPS (stopTimeout)", minutes)
        )
    }

    private fun fire(ctx: Context) {
        waiting = false
        pending = null
        val cfg = Prefs.config(ctx)
        if (stopOnStationary(cfg)) {
            Diag.event(ctx, "motion", "hết stopTimeout → stop() vì stopOnStationary=true")
            Prefs.setDesired(ctx, false)
            TrackingService.stop(ctx)
        } else {
            TrackingService.pause(ctx)
        }
    }

    /** Cử động trở lại → huỷ hẹn tắt GPS. */
    fun onMoving(ctx: Context? = null) {
        if (waiting) ctx?.let { Diag.log("cử động lại → huỷ hẹn tắt GPS") }
        waiting = false
        cancel()
    }

    private fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    /**
     * Gọi trên mỗi mẫu GPS ĐƯỢC NHẬN. Đang chờ tắt mà máy đã rời xa điểm neo quá
     * `stationaryRadius` thì huỷ hẹn — tức là Activity Recognition báo STILL sai.
     */
    fun onLocation(ctx: Context, lat: Double, lng: Double) {
        if (!waiting || anchorLat.isNaN()) return
        val radius = Prefs.config(ctx).optDouble("stationaryRadius", 150.0).coerceAtLeast(25.0)
        val d = Util.haversine(anchorLat, anchorLng, lat, lng)
        if (d > radius) {
            Diag.event(
                ctx, "motion",
                String.format(
                    java.util.Locale.US,
                    "đã đi %.0fm (> stationaryRadius %.0fm) → huỷ hẹn tắt GPS", d, radius
                )
            )
            onMoving(ctx)
        }
    }
}
