package com.rnbggeo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import org.json.JSONObject

/**
 * Tiết kiệm pin: lắng nghe cảm biến chuyển động (Activity Recognition).
 * Khi đứng yên -> dừng GPS; khi cử động -> bật lại GPS.
 *
 * Cũng bắn event onActivityChange. Lưu ý: intent của Activity Transition KHÔNG
 * kèm confidence, nên khi cần confidence thật sẽ hỏi thêm 1 lần
 * (requestActivityUpdates rồi remove ngay) — gọi là "one-shot".
 */
class MotionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.rnbggeo.MOTION_TRANSITION"
        const val ACTION_ACTIVITY_UPDATE = "com.rnbggeo.ACTIVITY_UPDATE"
        private const val REQ_ACTIVITY_UPDATE = 8801
        private const val FALLBACK_MS = 4000L

        fun buildRequest(): com.google.android.gms.location.ActivityTransitionRequest {
            fun t(type: Int, transition: Int): ActivityTransition =
                ActivityTransition.Builder()
                    .setActivityType(type)
                    .setActivityTransition(transition)
                    .build()
            val list = listOf(
                t(com.google.android.gms.location.DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
                t(com.google.android.gms.location.DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_EXIT),
                t(com.google.android.gms.location.DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
                t(com.google.android.gms.location.DetectedActivity.ON_FOOT, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
                t(com.google.android.gms.location.DetectedActivity.RUNNING, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
                t(com.google.android.gms.location.DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER),
                t(com.google.android.gms.location.DetectedActivity.ON_BICYCLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER)
            )
            return com.google.android.gms.location.ActivityTransitionRequest(list)
        }

        fun activityUpdatePendingIntent(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, REQ_ACTIVITY_UPDATE,
                Intent(ctx, MotionReceiver::class.java).setAction(ACTION_ACTIVITY_UPDATE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

        // trạng thái chờ kết quả one-shot
        private val handler = Handler(Looper.getMainLooper())
        private var fallback: Runnable? = null
        private var awaiting: JSONObject? = null

        /** Hẹn bật GPS sau `motionTriggerDelay` (giống lib gốc). Huỷ nếu có STILL. */
        private var pendingWake: Runnable? = null

        private fun scheduleWake(ctx: Context, delayMs: Long) {
            pendingWake?.let { handler.removeCallbacks(it) }
            val app = ctx.applicationContext
            val r = Runnable {
                pendingWake = null
                if (Prefs.desired(app)) TrackingService.start(app)
                Diag.event(app, "motion", "hết motionTriggerDelay → bật GPS kiểm chứng")
            }
            pendingWake = r
            handler.postDelayed(r, delayMs)
            Diag.event(
                ctx, "motion",
                String.format(java.util.Locale.US, "chờ %.1fs (motionTriggerDelay) rồi mới bật GPS", delayMs / 1000.0)
            )
        }

        private fun cancelPendingWake(ctx: Context) {
            if (pendingWake == null) return
            pendingWake?.let { handler.removeCallbacks(it) }
            pendingWake = null
            Diag.event(ctx, "motion", "huỷ motionTriggerDelay vì Motion API đã trở về STILL")
        }

        private fun emitActivity(
            context: Context, activity: String, confidence: Int, isMoving: Boolean, source: String
        ) {
            val o = JSONObject()
            o.put("activity", activity)
            o.put("confidence", confidence)
            o.put("isMoving", isMoving)
            o.put("timestamp", System.currentTimeMillis())
            o.put("source", source)
            Events.emit(context, Events.ACTIVITY, o.toString())
        }

        /** Hỏi confidence 1 lần; quá [FALLBACK_MS] không có kết quả thì bắn tạm với -1. */
        private fun requestConfidence(context: Context, activity: String, isMoving: Boolean) {
            val meta = JSONObject()
            meta.put("activity", activity)
            meta.put("isMoving", isMoving)
            awaiting = meta
            fallback?.let { handler.removeCallbacks(it) }
            val r = Runnable {
                val m = awaiting
                awaiting = null
                if (m != null) {
                    Diag.event(
                        context, "activity",
                        "${m.optString("activity")} — không lấy được confidence (timeout)"
                    )
                    emitActivity(context, m.optString("activity"), -1, Prefs.isMoving(context), "timeout")
                }
            }
            fallback = r
            handler.postDelayed(r, FALLBACK_MS)
            try {
                ActivityRecognition.getClient(context)
                    .requestActivityUpdates(0L, activityUpdatePendingIntent(context))
                    .addOnFailureListener { e ->
                        handler.removeCallbacks(r)
                        awaiting = null
                        Diag.event(context, "activity", "one-shot confidence FAILED: ${e.message}")
                        emitActivity(context, activity, -1, Prefs.isMoving(context), "failed")
                    }
            } catch (e: Exception) {
                handler.removeCallbacks(r)
                awaiting = null
                emitActivity(context, activity, -1, Prefs.isMoving(context), "error")
            }
        }

        private fun activityName(type: Int): String = when (type) {
            com.google.android.gms.location.DetectedActivity.IN_VEHICLE -> "in_vehicle"
            com.google.android.gms.location.DetectedActivity.ON_BICYCLE -> "on_bicycle"
            com.google.android.gms.location.DetectedActivity.ON_FOOT -> "on_foot"
            com.google.android.gms.location.DetectedActivity.STILL -> "still"
            com.google.android.gms.location.DetectedActivity.WALKING -> "walking"
            com.google.android.gms.location.DetectedActivity.RUNNING -> "running"
            else -> "unknown"
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // app có thể vừa được hệ thống dựng lại chỉ để nhận broadcast này
        Diag.attach(context)

        // nhánh one-shot: kết quả Activity Recognition đầy đủ (có confidence)
        if (intent.action == ACTION_ACTIVITY_UPDATE) {
            val ar = try { ActivityRecognitionResult.extractResult(intent) } catch (e: Exception) { null }
            val det = ar?.mostProbableActivity
            if (det != null) {
                val name = activityName(det.type)
                val conf = det.confidence
                val meta = awaiting
                awaiting = null
                fallback?.let { handler.removeCallbacks(it) }
                val moving = meta?.optBoolean("isMoving")
                    ?: Prefs.isMoving(context)
                Diag.event(context, "activity", "$name confidence=$conf% moving=$moving")
                emitActivity(context, name, conf, moving, "oneShot")
            }
            try {
                ActivityRecognition.getClient(context)
                    .removeActivityUpdates(activityUpdatePendingIntent(context))
            } catch (e: Exception) {}
            return
        }

        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        if (result.transitionEvents.isEmpty()) return

        val actCfg = Prefs.config(context).optJSONObject("activity") ?: JSONObject()
        val minConfidence = actCfg.optInt("minimumActivityRecognitionConfidence", 75)
        val triggerActivities = actCfg
            .optString("triggerActivities", "in_vehicle,on_bicycle,on_foot,running,walking")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val motionTriggerDelay = actCfg.optLong("motionTriggerDelay", 0L).coerceAtLeast(0L)

        // nếu intent có sẵn ActivityRecognitionResult (một số máy có) thì dùng luôn
        val inlineConfidence = try {
            ActivityRecognitionResult.extractResult(intent)?.mostProbableActivity?.confidence
        } catch (e: Exception) { null }

        for (ev in result.transitionEvents) {
            val isStill = ev.activityType == com.google.android.gms.location.DetectedActivity.STILL
            val entering = ev.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
            // STILL-EXIT = bắt đầu cử động; các loại khác ENTER = bắt đầu cử động.
            // Đây chỉ là GỢI Ý: IN_VEHICLE nghĩa là "đang ở trong xe", không có nghĩa
            // xe đang chạy. Trạng thái thật do MovementTracker xác nhận bằng GPS.
            val suggestsMovement = if (isStill) !entering else entering

            val actName = activityName(ev.activityType)
            val actualMoving = MovementTracker.isMoving()

            Diag.event(
                context, "motion",
                "$actName entering=$entering → gợi ý " +
                    (if (suggestsMovement) "CỬ ĐỘNG" else "ĐỨNG YÊN") +
                    " · GPS xác nhận moving=$actualMoving" +
                    " (desired=${Prefs.desired(context)}, enabled=${Prefs.enabled(context)})"
            )

            // onActivityChange — kèm confidence (hỏi one-shot nếu intent không có).
            // isMoving phản ánh trạng thái GPS đã xác nhận, không phải loại activity.
            if (inlineConfidence != null) {
                emitActivity(context, actName, inlineConfidence, actualMoving, "transition")
            } else {
                requestConfidence(context, actName, actualMoving)
            }

            Prefs.setActivity(context, actName)
            MovingState.currentlyMoving = actualMoving

            if (!suggestsMovement) {
                // AR báo đứng yên — chiều này đáng tin hơn. Không đặt isMoving=false ngay
                // (GPS sẽ xác nhận sau vài mẫu), nhưng hẹn stopTimeout để không giữ GPS mãi.
                cancelPendingWake(context)
                if (Prefs.desired(context) && Prefs.enabled(context)) {
                    StopDetector.onStill(context)
                }
            } else {
                // Gợi ý có cử động: chỉ BẬT GPS để kiểm chứng. KHÔNG đánh dấu đang di
                // chuyển và KHÔNG huỷ hẹn tắt GPS — nếu là báo nhầm (ngồi yên trong xe,
                // cắm sạc) thì MovementTracker sẽ xác nhận đứng yên và hẹn lại.
                //
                // Giống lib gốc: lọc theo triggerActivities + minimumActivityRecognitionConfidence.
                // Riêng sự kiện STILL-EXIT (vừa rời trạng thái đứng yên) thì không lọc được
                // vì AR không nói là loại hoạt động nào.
                var allow = true
                if (!isStill) {
                    if (actName.lowercase() !in triggerActivities) {
                        allow = false
                        Diag.event(context, "motion", "$actName không nằm trong triggerActivities → bỏ qua")
                    } else if (inlineConfidence != null && inlineConfidence < minConfidence) {
                        allow = false
                        Diag.event(
                            context, "motion",
                            "$actName confidence=$inlineConfidence% < $minConfidence% → bỏ qua"
                        )
                    }
                }
                if (allow && Prefs.desired(context)) {
                    if (motionTriggerDelay > 0) scheduleWake(context, motionTriggerDelay)
                    else TrackingService.start(context)
                }
            }
        }
    }
}

object MovingState {
    @Volatile
    var currentlyMoving: Boolean = false
}
