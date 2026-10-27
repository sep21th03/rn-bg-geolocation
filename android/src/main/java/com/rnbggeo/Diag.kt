package com.rnbggeo

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nhật ký chẩn đoán — tồn tại qua nhiều giờ, không bị logcat cuốn mất.
 *
 * 1. Ghi ra file trong thư mục NGOÀI của app:
 *    /sdcard/Android/data/<pkg>/files/rnbggeo.log
 *    → `adb pull` đọc được, không cần root, không cần run-as (release build cũng được).
 * 2. Tuỳ chọn đẩy từng sự kiện lên server (config `postDiagEvents`) → URL + "/diag",
 *    để còn đường lấy dữ liệu thứ hai nếu không cầm được máy.
 *
 * Bật bằng config `logToFile: true`. Mặc định TẮT (thư viện không tự ghi file của người khác).
 */
object Diag {
    private const val TAG = "rnbggeo"
    private const val FILE_NAME = "rnbggeo.log"
    private const val ROTATED_NAME = "rnbggeo.1.log"
    private const val DEFAULT_MAX_BYTES = 16L * 1024 * 1024

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var file: File? = null
    @Volatile private var enabled = false
    @Volatile private var maxBytes = DEFAULT_MAX_BYTES
    @Volatile private var configured = false

    /** Mức log: 0 = tắt, 5 = verbose (giống setLogLevel của lib gốc). */
    @Volatile private var level = 5

    fun setLevel(n: Int) { level = n.coerceIn(0, 5) }
    fun getLevel(): Int = level

    /**
     * Gọi ở MỌI lối vào có thể chạy khi tiến trình vừa được hệ thống dựng lại
     * (BroadcastReceiver, Service). Không có bước này thì sự kiện đầu tiên — ví dụ
     * geofence ENTER đánh thức app lúc 7h sáng — chỉ lên được server mà KHÔNG vào file,
     * vì `configure()` chỉ chạy trong ready() khi người dùng mở app.
     */
    fun attach(ctx: Context) {
        if (configured) return
        configure(ctx, Prefs.config(ctx.applicationContext))
    }

    /** Gọi từ ready()/setConfig(). An toàn khi gọi nhiều lần. */
    fun configure(ctx: Context, cfg: JSONObject) {
        configured = true
        enabled = cfg.optBoolean("logToFile", false)
        maxBytes = cfg.optLong("logMaxBytes", 0L).let { if (it > 0) it else DEFAULT_MAX_BYTES }
        file = if (!enabled) null else {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            try { if (!dir.exists()) dir.mkdirs() } catch (e: Exception) {}
            File(dir, FILE_NAME)
        }
        if (enabled) log("---- Diag bật: ${file?.absolutePath} ----")
    }

    fun isEnabled(): Boolean = enabled

    fun logPath(): String = file?.absolutePath ?: ""

    fun logSize(): Long = try { file?.length() ?: 0L } catch (e: Exception) { 0L }

    /** Ghi log thường (chỉ file + logcat). */
    fun log(msg: String) {
        Log.d(TAG, msg)
        write(msg)
    }

    /**
     * Sự kiện có ý nghĩa (service bật/tắt, chuyển động, geofence, kết quả gửi server…).
     * Ghi file và — nếu bật `postDiagEvents` — đẩy lên server.
     *
     * LUÔN kèm device_id: trước đây thiếu khoá này nên mọi dòng diag của Android đều nằm
     * chung một rổ "không rõ máy nào" trên server. Khi chẩn đoán đoạn hành trình bị mất
     * của SM-A127F (23/10) phải suy luận thủ công qua giá trị `odom=` mới biết dòng nào
     * của máy nào — không chấp nhận được khi có nhiều máy chạy cùng lúc.
     */
    fun event(ctx: Context, kind: String, msg: String, extra: JSONObject? = null) {
        val line = "$kind: $msg"
        Log.d(TAG, line)
        write(line)
        val cfg = Prefs.config(ctx)
        if (!cfg.optBoolean("postDiagEvents", false)) return
        val url = cfg.optString("url").trim()
        if (url.isEmpty()) return
        val o = JSONObject()
        o.put("kind", kind)
        o.put("message", msg)
        o.put("timestamp", System.currentTimeMillis())
        o.put("device_id", Util.DeviceId.id(ctx))
        o.put("device_name", Util.DeviceId.name())
        o.put("device_platform", "android")
        extra?.keys()?.forEach { k -> o.put(k, extra.opt(k)) }
        HttpUploader.postDiag(ctx, url.trimEnd('/') + "/diag", o)
    }

    private fun write(line: String) {
        val f = file ?: return
        if (!enabled) return
        if (level <= 0) return
        synchronized(lock) {
            try {
                if (f.length() > maxBytes) rotate(f)
                f.appendText("${fmt.format(Date())}  $line\n")
            } catch (e: Exception) {
                // hết chỗ / thẻ nhớ bị gỡ — bỏ qua, không được làm chết service
            }
        }
    }

    /** Giữ lại phần cũ vào rnbggeo.1.log rồi mở file mới — không mất dữ liệu. */
    private fun rotate(f: File) {
        try {
            val old = File(f.parentFile, ROTATED_NAME)
            if (old.exists()) old.delete()
            f.renameTo(old)
            f.createNewFile()
        } catch (e: Exception) {
        }
    }

    /** Đọc `maxBytes` cuối của file (dùng cho API getLog). */
    fun tail(maxBytes: Int): String {
        val f = file ?: return ""
        return try {
            if (!f.exists()) return ""
            val len = f.length()
            val start = if (len > maxBytes) len - maxBytes else 0L
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun clear() {
        synchronized(lock) {
            try { file?.writeText("") } catch (e: Exception) {}
        }
    }

    /** Xoá hẳn file log (giống destroyLog của lib gốc). */
    fun destroy() {
        synchronized(lock) {
            try { file?.delete() } catch (e: Exception) {}
            try { File(file?.parentFile, ROTATED_NAME).delete() } catch (e: Exception) {}
        }
    }
}
