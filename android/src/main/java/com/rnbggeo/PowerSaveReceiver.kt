package com.rnbggeo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.json.JSONObject

/**
 * Phát hiện chế độ tiết kiệm pin bật/tắt.
 * Bắn event [Events.POWERSAVE] lên JS với { isPowerSaveMode: boolean }.
 */
class PowerSaveReceiver : BroadcastReceiver() {

    companion object {
        fun isPowerSaveMode(ctx: Context): Boolean {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isPowerSaveMode
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Diag.attach(context)
        val mode = isPowerSaveMode(context)
        val o = JSONObject()
        o.put("isPowerSaveMode", mode)
        Diag.event(
            context, "powersave",
            if (mode) "chế độ tiết kiệm pin BẬT" else "chế độ tiết kiệm pin TẮT",
            o
        )
        Events.emit(context, Events.POWERSAVE, o.toString())
    }
}
