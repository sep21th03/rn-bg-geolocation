package com.rnbggeo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Thông báo hệ thống khi vào/ra geofence.
 *
 * Vì sao phải làm ở native: khi app bị vuốt khỏi recent hoặc màn hình khoá, JS
 * không còn chạy — nếu chỉ bắn `onGeofence` sang JS thì người dùng không thấy gì.
 * Thông báo này do BroadcastReceiver / TrackingService phát, không phụ thuộc JS.
 *
 * Tên hiển thị lấy theo thứ tự: `geofence.name` → `geofence.extras.note` →
 * `identifier`. Nhờ vậy có thể đặt tên "Nhà", "Công ty" để sau này dùng cho
 * chấm công hoặc nhắc việc.
 *
 * Tắt toàn bộ bằng config `geofenceNotifications: false`; tắt từng vùng bằng
 * `notification: false` trong geofence đó.
 */
object GeofenceNotifier {

    private const val CHANNEL_ID = "rn_bg_geo_geofence"
    private const val TAG = "geofence"

    fun notify(ctx: Context, fence: JSONObject?, action: Int, lat: Double?, lng: Double?) {
        try {
            val cfg = Prefs.config(ctx)
            if (!cfg.optBoolean("geofenceNotifications", true)) return
            val f = fence ?: return
            if (!f.optBoolean("notification", true)) return

            val enabled = when (action) {
                1 -> f.optBoolean("notifyOnEntry", true)
                2 -> f.optBoolean("notifyOnExit", true)
                else -> f.optBoolean("notifyOnDwell", false)
            }
            if (!enabled) return

            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Diag.log("geofence notify: thiếu quyền POST_NOTIFICATIONS")
                return
            }

            val identifier = f.optString("identifier")
            val name = f.optString("name").ifEmpty {
                f.optJSONObject("extras")?.optString("note").orEmpty().ifEmpty { identifier }
            }
            val (title, verb) = when (action) {
                1 -> "Đã đến $name" to "vào"
                2 -> "Đã rời $name" to "rời"
                else -> "Đang ở $name" to "nán lại"
            }
            val note = f.optJSONObject("extras")?.optString("note").orEmpty()
            val where = if (lat != null && lng != null) {
                String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng)
            } else ""
            val text = buildString {
                append("Lúc ").append(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(java.util.Date()))
                if (where.isNotEmpty()) append(" • ").append(where)
                if (note.isNotEmpty() && note != name) append(" • ").append(note)
            }

            createChannel(ctx)
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val pi = PendingIntent.getActivity(
                ctx, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            // Mỗi vùng + hành động một id để ENTER và EXIT không ghi đè nhau.
            val id = (identifier.hashCode() and 0x7fffffff) % 100000 + action * 100000
            NotificationManagerCompat.from(ctx).notify(id, notif)
            Diag.event(ctx, TAG, "thông báo: $title ($verb) [$identifier]")
        } catch (e: Exception) {
            Diag.log("geofence notify lỗi: ${e.message}")
        }
    }

    private fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Geofence",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Báo khi vào/ra khu vực đã đặt tên" }
        nm.createNotificationChannel(ch)
    }
}
