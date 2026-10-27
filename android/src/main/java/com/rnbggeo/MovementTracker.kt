package com.rnbggeo

import android.content.Context
import org.json.JSONObject
import kotlin.math.max

/**
 * Xác nhận "đang di chuyển" bằng GPS, KHÔNG tin thẳng Activity Recognition.
 *
 * Vì sao phải có lớp này:
 *  - `DetectedActivity.IN_VEHICLE` chỉ có nghĩa "đang ở trong xe", không có nghĩa xe đang
 *    chạy. Ngồi yên trong xe (hoặc cắm sạc trong xe) Android vẫn báo IN_VEHICLE. Code cũ
 *    đánh dấu `is_moving = true` ngay khi nhận transition IN_VEHICLE ENTER, nên máy nằm
 *    yên vẫn hiện "ĐANG DI CHUYỂN" mãi.
 *  - Tốc độ GPS cũng nhiễu: một mẫu 1,22 m/s khi đang đứng yên đủ để code cũ huỷ hẹn tắt
 *    GPS. Phải nhìn chuỗi mẫu + quãng dịch chuyển thật.
 *
 * Quy tắc:
 *  - DI CHUYỂN khi: dịch khỏi mốc >= [wakeDistance] (mặc định 75 m), HOẶC
 *    >= [MOVING_SAMPLES] mẫu liên tiếp tốc độ >= 1,1 m/s và đã đi >= [CONFIRM_DISTANCE].
 *  - ĐỨNG YÊN khi: >= [STILL_SAMPLES] mẫu liên tiếp tốc độ <= 0,7 m/s và cả chuỗi nằm
 *    trong vòng [STILL_DISTANCE].
 *  - Mốc được dời về vị trí hiện tại mỗi khi chốt trạng thái, nên GPS trôi chậm không
 *    cộng dồn thành "đã đi xa".
 *
 * Toàn bộ trạng thái nằm trong bộ nhớ tiến trình; [reset] được gọi mỗi lần GPS đăng ký
 * lại để không dùng mốc cũ từ lần chạy trước.
 */
object MovementTracker {

    private const val MOVING_SPEED = 1.1f

    /**
     * Ngưỡng riêng cho đi bộ. Fused Location hay trả speed=0 khi đi bộ trong nhà /
     * dưới tán cây, nên chỉ dựa vào speed là không đủ. Khi Activity Recognition báo
     * walking/on_foot/running, ta xác nhận bằng QUÃNG DỊCH CHUYỂN THẬT.
     */
    private const val WALK_SPEED = 0.8f
    private const val WALK_CONFIRM_DISTANCE = 15.0
    private const val WALK_CONFIRM_SAMPLES = 3

    private const val STILL_SPEED = 0.7f
    private const val MOVING_SAMPLES = 3
    private const val STILL_SAMPLES = 4

    /** Đi được chừng này trong lúc đang xét thì đủ chắc là di chuyển thật. */
    private const val CONFIRM_DISTANCE = 25.0

    /** Cả chuỗi "đứng yên" phải nằm trong vòng này (GPS đô thị trôi tới ~40 m). */
    private const val STILL_DISTANCE = 40.0

    /** Đi bộ thì siết chặt hơn: đi được 10 m mà vẫn chậm mới coi là đứng yên. */
    private const val WALK_STILL_DISTANCE = 10.0

    private var anchorLat = Double.NaN
    private var anchorLng = Double.NaN
    private var anchorSamples = 0
    private var movingRun = 0
    private var stillRun = 0

    @Volatile
    private var moving = false

    fun isMoving(): Boolean = moving

    /** Quên mốc cũ — gọi khi bắt đầu/ngừng đăng ký GPS. */
    fun reset(ctx: Context) {
        anchorLat = Double.NaN
        anchorLng = Double.NaN
        anchorSamples = 0
        movingRun = 0
        stillRun = 0
        moving = false
        Prefs.setMoving(ctx, false)
    }

    /**
     * Nạp một mẫu GPS ĐÃ QUA BỘ LỌC. Trả về trạng thái di chuyển đã xác nhận.
     *
     * @param speed tốc độ m/s; truyền số âm nếu mẫu không có tốc độ.
     */
    fun onLocation(
        ctx: Context,
        lat: Double,
        lng: Double,
        speed: Float,
        hasSpeed: Boolean
    ): Boolean {
        val cfg = Prefs.config(ctx)
        val activity = Prefs.activity(ctx)
        // AR chỉ là gợi ý, nhưng khi nó nói đang đi bộ thì cho phép xác nhận bằng
        // quãng đường ngắn hơn và bằng speed thấp hơn. Nhiều máy trả speed=0.0
        // trong lúc đi bộ (nhà, hầm, tán cây) nên nếu chỉ xét speed sẽ không bao
        // giờ chuyển sang "đang di chuyển".
        val walking = activity == "walking" || activity == "on_foot" || activity == "running"
        val movingSpeed = if (walking) WALK_SPEED else MOVING_SPEED
        val confirmDistance = if (walking) WALK_CONFIRM_DISTANCE else CONFIRM_DISTANCE
        val requiredSamples = if (walking) WALK_CONFIRM_SAMPLES else MOVING_SAMPLES

        val stationaryRadius = cfg.optDouble("stationaryRadius", 150.0).coerceAtLeast(25.0)
        // Một mẫu đã cách mốc xa hơn [wakeDistance] thì không cần chờ thêm mẫu nữa.
        // Đặt >= 100 m để GPS trôi trong đô thị không bị coi là di chuyển; đi thật thì
        // chuỗi 3 mẫu nhanh + 25 m đã đủ xác nhận trước khi tới ngưỡng này.
        val wakeDistance = max(100.0, stationaryRadius * 0.6)

        if (anchorLat.isNaN() || anchorLng.isNaN()) {
            anchorLat = lat
            anchorLng = lng
            anchorSamples = 1
            update(ctx, hasSpeed && speed >= movingSpeed, "mẫu đầu tiên")
            return moving
        }

        anchorSamples++
        val d = Util.haversine(anchorLat, anchorLng, lat, lng)

        if (hasSpeed) {
            when {
                speed >= movingSpeed -> {
                    movingRun++
                    stillRun = 0
                }
                speed <= STILL_SPEED -> {
                    stillRun++
                    movingRun = 0
                }
                else -> {
                    movingRun = 0
                    stillRun = 0
                }
            }
        }

        if (d >= wakeDistance) {
            moveAnchor(lat, lng)
            update(ctx, true, String.format(java.util.Locale.US, "rời mốc %.0fm", d))
        } else if (walking && anchorSamples >= WALK_CONFIRM_SAMPLES && d >= confirmDistance) {
            // Đi bộ: xác nhận bằng quãng đường, không phụ thuộc speed (có thể = 0).
            val samples = anchorSamples
            moveAnchor(lat, lng)
            update(
                ctx, true,
                String.format(
                    java.util.Locale.US,
                    "đi bộ %.0fm (%d mẫu, AR=%s)", d, samples, activity
                )
            )
        } else if (movingRun >= requiredSamples && d >= confirmDistance) {
            val samples = movingRun
            moveAnchor(lat, lng)
            update(
                ctx, true,
                String.format(java.util.Locale.US, "%d mẫu nhanh, đi %.0fm", samples, d)
            )
        } else if (stillRun >= STILL_SAMPLES && d < (if (walking) WALK_STILL_DISTANCE else STILL_DISTANCE)) {
            val samples = stillRun
            moveAnchor(lat, lng)
            update(
                ctx, false,
                String.format(java.util.Locale.US, "%d mẫu chậm, lệch %.0fm", samples, d)
            )
            if (cfg.optBoolean("motionDetection", true)) StopDetector.onStill(ctx)
        }

        return moving
    }

    private fun moveAnchor(lat: Double, lng: Double) {
        anchorLat = lat
        anchorLng = lng
        anchorSamples = 0
        movingRun = 0
        stillRun = 0
    }

    private fun update(ctx: Context, value: Boolean, reason: String) {
        val changed = moving != value || Prefs.isMoving(ctx) != value
        moving = value
        Prefs.setMoving(ctx, value)
        if (!changed) return

        Diag.event(
            ctx, "motion",
            "GPS xác nhận ${if (value) "DI CHUYỂN" else "ĐỨNG YÊN"} ($reason)"
        )
        if (value && Prefs.config(ctx).optBoolean("motionDetection", true)) {
            StopDetector.onMoving(ctx)
        }

        // onMotionChange cho JS — chỉ bắn khi trạng thái THẬT SỰ đổi.
        val o = JSONObject()
        o.put("isMoving", value)
        o.put("activity", Prefs.activity(ctx))
        Prefs.lastLocation(ctx)?.let { o.put("location", it) }
        Events.emit(ctx, Events.MOTION, o.toString())
    }
}
