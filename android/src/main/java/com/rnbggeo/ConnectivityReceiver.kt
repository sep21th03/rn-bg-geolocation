package com.rnbggeo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject

/**
 * Phát hiện thay đổi trạng thái mạng (online/offline).
 * Bắn event [Events.CONNECTIVITY] lên JS với { connected: boolean, type: string }.
 */
class ConnectivityReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "android.net.conn.CONNECTIVITY_CHANGE"

        fun currentStatus(ctx: Context): JSONObject {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            val connected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val type = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val o = JSONObject()
            o.put("connected", connected)
            o.put("type", type)
            return o
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Diag.attach(context)
        val status = currentStatus(context)
        Diag.event(
            context, "connectivity",
            if (status.optBoolean("connected")) "mạng có lại (${status.optString("type")})"
            else "mất mạng",
            status
        )
        Events.emit(context, Events.CONNECTIVITY, status.toString())
    }
}
