package com.rnbggeo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Khởi động lại tracking sau reboot nếu trước đó đang bật. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        Diag.attach(context)
        if (Prefs.desired(context)) {
            Diag.event(context, "service", "Máy vừa khởi động — bật lại theo dõi theo cấu hình đã lưu")
            BgGeoModule.reregisterNative(context.applicationContext)
            TrackingService.start(context.applicationContext)
        }
    }
}
