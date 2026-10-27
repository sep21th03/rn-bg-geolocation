package com.rnbggeo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener

/**
 * Đánh thức service bằng cảm biến `TYPE_SIGNIFICANT_MOTION` khi máy đang tạm nghỉ.
 *
 * Vì sao cần: Activity Recognition Transition có thể trễ rất lâu. Nhật ký thật ngày
 * 19/10/2026 cho thấy máy rời nhà khoảng 13:24–13:26 nhưng tới 13:30:20 mới nhận được
 * transition `STILL EXIT` — mất 1,5 km đầu của chuyến. Cảm biến significant-motion là
 * cảm biến one-shot, gần như không tốn pin, bắn ngay khi có chuyển động đủ lớn.
 *
 * LƯU Ý API: significant-motion là **trigger sensor**, phải dùng
 * `requestTriggerSensor(TriggerEventListener, …)`. Dùng `registerListener` với
 * `SensorEventListener` sẽ trả về false và không bao giờ nhận được sự kiện.
 *
 * Cảm biến chỉ báo "có chuyển động", KHÔNG nói đang di chuyển hay đứng yên. Vì vậy nó
 * chỉ dùng để BẬT GPS; trạng thái cuối cùng do [MovementTracker] quyết định bằng GPS.
 */
object SignificantMotion : TriggerEventListener() {

    private var appCtx: Context? = null
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var armed = false

    /** Gắn cảm biến — gọi khi service chuyển sang trạng thái tạm nghỉ. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (armed) return
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (s == null) {
            Diag.log("significant-motion: máy không có cảm biến — chỉ dựa vào Activity Recognition")
            return
        }
        sensorManager = sm
        sensor = s
        armed = runCatching { sm.requestTriggerSensor(this, s) }
            .onFailure { Diag.log("significant-motion: request lỗi ${it.message}") }
            .getOrDefault(false)
        Diag.log("significant-motion: đã gắn cảm biến đánh thức (armed=$armed)")
    }

    /** Gỡ cảm biến — gọi khi GPS chạy lại hoặc service dừng. */
    fun stop(ctx: Context) {
        val sm = sensorManager
        val s = sensor
        if (armed && sm != null && s != null) {
            runCatching { sm.cancelTriggerSensor(this, s) }
            Diag.log("significant-motion: đã gỡ cảm biến")
        }
        armed = false
        sensorManager = null
        sensor = null
    }

    override fun onTrigger(event: TriggerEvent?) {
        val ctx = appCtx ?: return
        // Cảm biến one-shot: tự ngắt sau khi bắn; đánh dấu để lần tạm nghỉ sau gắn lại.
        armed = false
        Diag.event(ctx, "motion", "significant-motion: có chuyển động → bật GPS kiểm chứng")
        if (Prefs.desired(ctx)) {
            TrackingService.start(ctx)
        }
    }
}
