package com.rnbggeo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object HttpUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Mọi thao tác mạng PHẢI chạy ở đây. `sync()` được gọi từ main thread
     * (LocationCallback chạy ở mainLooper) nên nếu gọi execute() trực tiếp sẽ
     * ném NetworkOnMainThreadException và bị nuốt im lặng.
     */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rnbggeo-uploader").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())
    private var networkCallbackRegistered = false
    private var retryScheduled = false
    private const val RETRY_DELAY_MS = 15_000L

    /** Chỉ cho một lượt sync chạy/lượt chờ tại một thời điểm. */
    private val syncQueued = AtomicBoolean(false)

    /** Có yêu cầu sync đến trong lúc đang chạy → chạy thêm một lượt sau khi xong. */
    private val syncAgain = AtomicBoolean(false)

    private fun hasNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** When the network comes back and autoSync is on, flush the queue. */
    fun registerConnectivityListener(ctx: Context) {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Diag.log("mạng có lại → thử gửi bù")
                val cfg = Prefs.config(ctx.applicationContext)
                if (cfg.optBoolean("autoSync", true)) sync(ctx.applicationContext)
            }
        })
    }

    private fun scheduleRetry(ctx: Context) {
        if (retryScheduled) return
        retryScheduled = true
        main.postDelayed({
            retryScheduled = false
            sync(ctx.applicationContext)
        }, RETRY_DELAY_MS)
    }

    /**
     * Upload pending records. Records are marked synced only after a successful response,
     * so an offline device simply keeps extending the SQLite queue.
     *
     * Không blocking: đẩy việc thật sang [executor] rồi trả về ngay.
     */
    fun sync(ctx: Context): Boolean {
        val appCtx = ctx.applicationContext
        val url = Prefs.config(appCtx).optString("url").trim()
        if (url.isEmpty()) return false
        // Trước đây mỗi mẫu GPS lại đẩy một task vào executor. Nếu mạng chậm, các
        // task xếp chồng, mỗi task lại query DB + gửi HTTP → nóng máy vô ích.
        if (!syncQueued.compareAndSet(false, true)) {
            syncAgain.set(true)
            return true
        }
        executor.execute {
            try {
                runCatching { doSync(appCtx) }.onFailure { Diag.log("sync crashed: $it") }
            } finally {
                syncQueued.set(false)
                // Điểm mới đến trong lúc đang gửi → chạy nốt một lượt, tránh bỏ sót.
                if (syncAgain.getAndSet(false)) sync(appCtx)
            }
        }
        return true
    }

    private fun doSync(appCtx: Context) {
        val cfg = Prefs.config(appCtx)
        val url = cfg.optString("url").trim()
        if (url.isEmpty()) return
        val db = LocationDb.get(appCtx)
        registerConnectivityListener(appCtx)

        if (!hasNetwork(appCtx)) {
            Diag.log("sync: offline, ${db.count()} điểm chờ")
            emitHttp(appCtx, false, 0, "offline", db.count())
            scheduleRetry(appCtx)
            return
        }

        val limit = if (cfg.optInt("maxBatchSize", -1) > 0) cfg.optInt("maxBatchSize") else 500
        val pending = db.pending(limit)
        val n = pending.length()
        if (n == 0) return

        val root = cfg.optString("httpRootProperty", "location")
        val headers = cfg.optJSONObject("headers")
        val extra = cfg.optJSONObject("params")

        fun wrap(payload: JSONArray): JSONObject {
            val o = JSONObject()
            if (!root.isEmpty() && root != ".") o.put(root, payload) else o.put("batch", payload)
            extra?.keys()?.forEach { k -> o.put(k, extra.opt(k)) }
            return o
        }

        if (cfg.optBoolean("batchSync", false)) {
            // one request with an array of locations
            val body = wrap(pending)
            post(appCtx, url, headers, body) { ok, code, text ->
                // Đánh dấu đã gửi, KHÔNG xoá — giữ lịch sử để vẽ hành trình
                if (ok) db.markSynced(uuidOf(pending)) else scheduleRetry(appCtx)
                Diag.log("sync batch: $n điểm, ok=$ok code=$code, còn ${db.count()} chờ, tổng ${db.total()}")
                emitHttp(appCtx, ok, code, text, n)
            }
            return
        }

        // one request per record, sequential
        var allOk = true
        for (i in 0 until n) {
            val loc = pending.getJSONObject(i)
            val single = JSONObject()
            if (!root.isEmpty() && root != ".") single.put(root, loc) else single.put("location", loc)
            extra?.keys()?.forEach { k -> single.put(k, extra.opt(k)) }
            val ok = postSync(url, headers, single)
            if (ok) {
                db.markSynced(listOf(loc.optString("uuid")))
            } else {
                allOk = false
                break
            }
        }
        Diag.log("sync: $n điểm, allOk=$allOk, còn ${db.count()} chờ, tổng ${db.total()}")
        if (!allOk) scheduleRetry(appCtx)
        emitHttp(appCtx, allOk, if (allOk) 200 else 0, "", n)
    }

    fun postGeofenceEvent(ctx: Context, event: JSONObject) {
        val appCtx = ctx.applicationContext
        val cfg = Prefs.config(appCtx)
        val url = cfg.optString("url").trim()
        if (url.isEmpty()) return
        val geoUrl = url.trimEnd('/') + "/geofences"
        val body = JSONObject()
        val root = cfg.optString("httpRootProperty", "location")
        if (!root.isEmpty() && root != ".") body.put(root, event) else body.put("geofence", event)
        val headers = cfg.optJSONObject("headers")
        // Không dùng executor của sync: một endpoint /diag chậm sẽ chặn việc gửi
        // điểm GPS. OkHttp dispatcher tự chạy nền và callback rỗng là đủ.
        post(appCtx, geoUrl, headers, body) { ok, _, _ ->
            Diag.log("geofence post → $ok")
            emitHttp(appCtx, ok, if (ok) 200 else 0, "", 1)
        }
    }

    /** Đẩy một sự kiện chẩn đoán lên server (best-effort, không retry, không chặn). */
    fun postDiag(ctx: Context, url: String, event: JSONObject) {
        val headers = Prefs.config(ctx.applicationContext).optJSONObject("headers")
        post(ctx, url, headers, event) { _, _, _ -> }
    }

    /**
     * Đẩy file log chẩn đoán lên server: POST `<url>/logs`.
     *
     * VÌ SAO CẦN: log của lib nằm ở thẻ nhớ của chính máy (`.../files/rnbggeo.log`).
     * Trước đây muốn đọc phải cắm cáp + `adb pull` — không làm được khi máy đang ở xa,
     * và chính vì thế mà đoạn hành trình bị mất của SM-A127F sáng 23/10 không chốt được
     * nguyên nhân. Có hàm này thì chỉ cần bấm một nút trong app.
     *
     * Chạy trên [executor] (không phải main thread) rồi trả kết quả về main thread.
     * `maxBytes` chặn trần dữ liệu gửi đi (server nhận tối đa 8 MB).
     */
    fun uploadLog(ctx: Context, maxBytes: Int, cb: (Boolean, Int, String) -> Unit) {
        val appCtx = ctx.applicationContext
        val cfg = Prefs.config(appCtx)
        val url = cfg.optString("url").trim()
        if (url.isEmpty()) {
            main.post { cb(false, 0, "chưa cấu hình url") }
            return
        }
        val text = Diag.tail(maxBytes)
        if (text.isEmpty()) {
            main.post { cb(false, 0, "log rỗng — kiểm tra logToFile") }
            return
        }
        val payload = JSONObject()
        payload.put("device_id", Util.DeviceId.id(appCtx))
        payload.put("device_name", Util.DeviceId.name())
        payload.put("device_platform", "android")
        payload.put("filename", "rnbggeo-" + Util.DeviceId.id(appCtx) + ".log")
        payload.put("bytes", text.length)
        payload.put("content", text)
        // Bọc theo `httpRootProperty` giống sự kiện geofence/diag để backend chỉ cần một
        // cách đọc body. Server cũng nhận được dict trần.
        val root = cfg.optString("httpRootProperty", "location")
        val body = JSONObject()
        if (root.isEmpty() || root == ".") body.put("log", payload) else body.put(root, payload)
        val headers = cfg.optJSONObject("headers")
        val target = url.trimEnd('/') + "/logs"
        executor.execute {
            val res = postSyncDetailed(target, headers, body)
            Diag.log("uploadLog → ${res.second} (${text.length} ký tự)")
            main.post { cb(res.first, res.second, res.third) }
        }
    }

    /** Như [postSync] nhưng trả cả mã HTTP + nội dung phản hồi (dùng cho uploadLog). */
    private fun postSyncDetailed(
        url: String, headers: JSONObject?, body: JSONObject
    ): Triple<Boolean, Int, String> {
        return try {
            client.newCall(buildRequest(url, headers, body)).execute().use {
                val text = it.body?.string() ?: ""
                Triple(it.isSuccessful, it.code, text)
            }
        } catch (e: Exception) {
            Diag.log("postSyncDetailed exception: ${e.javaClass.simpleName}: ${e.message}")
            Triple(false, 0, e.message ?: "error")
        }
    }

    private fun uuidOf(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("uuid"))
        return out
    }

    private fun emitHttp(ctx: Context, ok: Boolean, code: Int, text: String, count: Int) {
        val o = JSONObject()
        o.put("success", ok)
        o.put("status", code)
        o.put("responseText", if (text.length > 500) text.substring(0, 500) else text)
        o.put("count", count)
        Events.emit(ctx, Events.HTTP, o.toString())
    }

    private fun buildRequest(url: String, headers: JSONObject?, body: JSONObject): Request {
        val rb = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val b = Request.Builder().url(url).post(rb)
        b.header("content-type", "application/json")
        headers?.keys()?.forEach { k -> b.header(k, headers.optString(k)) }
        return b.build()
    }

    private fun post(ctx: Context, url: String, headers: JSONObject?, body: JSONObject, cb: (Boolean, Int, String) -> Unit): Boolean {
        try {
            client.newCall(buildRequest(url, headers, body)).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Diag.log("post fail: ${e.message}")
                    cb(false, 0, e.message ?: "network error")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string() ?: ""
                        Diag.log("post → ${it.code}")
                        cb(it.isSuccessful, it.code, text)
                    }
                }
            })
        } catch (e: Exception) {
            Diag.log("post exception: ${e.message}")
            cb(false, 0, e.message ?: "error")
        }
        return true
    }

    private fun postSync(url: String, headers: JSONObject?, body: JSONObject): Boolean {
        return try {
            client.newCall(buildRequest(url, headers, body)).execute().use {
                if (!it.isSuccessful) Diag.log("postSync → ${it.code} ${it.message}")
                it.isSuccessful
            }
        } catch (e: Exception) {
            Diag.log("postSync exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
