package com.rnbggeo

import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Đếm số VỆ TINH điện thoại đang NHÌN THẤY và số vệ tinh ĐANG DÙNG ĐƯỢC để tính vị trí.
 *
 * VÌ SAO CẦN — câu hỏi chẩn đoán mà log cũ KHÔNG trả lời được:
 *   "Máy không lấy được vị trí" có 2 nguyên nhân hoàn toàn khác nhau về cách xử lý:
 *     (a) máy KHÔNG nhìn thấy vệ tinh nào  → anten/GNSS yếu-hỏng, hoặc máy bị che sóng
 *                                            → PHẦN CỨNG / chỗ để máy
 *     (b) máy thấy 10–15 vệ tinh mà vẫn không ra được vị trí
 *                                            → PHẦN MỀM (Play Services / hệ điều hành)
 *   Trước đây app chỉ biết "không có mẫu vị trí" nên không phân biệt được (a) và (b).
 *
 * Bằng chứng dẫn tới việc thêm lớp này (đo 22–23/10 trên SM-A127F):
 *   • 66 % mẫu có accuracy > 15 m ⇒ fix kiểu WiFi/cell, gần như không dùng vệ tinh;
 *     trong khi SM-A066B cùng lúc đạt accuracy trung vị 1,7 m (86 % mẫu ≤ 5 m).
 *   • Đường đi trên bản đồ có bước nhảy 131 km/h và 115 km/h — bất khả thi trên đường đó.
 *
 * Chỉ ĐỌC trạng thái của phiên GNSS đang chạy — KHÔNG tự bật GNSS, gần như không tốn pin.
 * iOS không có API công khai tương đương nên tính năng này chỉ có trên Android.
 */
object GnssWatcher {

    /** Số vệ tinh nhìn thấy được. -1 = chưa có dữ liệu. */
    @Volatile
    var visible: Int = -1
        private set

    /** Số vệ tinh đang được dùng để tính vị trí. -1 = chưa có dữ liệu. */
    @Volatile
    var used: Int = -1
        private set

    /** Lần cuối nhận được thông tin vệ tinh (elapsedRealtime ms). 0 = chưa/đã ngừng. */
    @Volatile
    var lastUpdateAt: Long = 0L
        private set

    private var callback: GnssStatus.Callback? = null
    private var lm: LocationManager? = null

    fun start(ctx: Context) {
        if (callback != null) return
        val appCtx = ctx.applicationContext
        val mgr = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var v = 0
                var u = 0
                for (i in 0 until status.satelliteCount) {
                    v++
                    if (status.usedInFix(i)) u++
                }
                visible = v
                used = u
                lastUpdateAt = SystemClock.elapsedRealtime()
            }

            override fun onStopped() {
                // GNSS ngừng hẳn (hệ thống thu hồi / người dùng tắt vị trí).
                lastUpdateAt = 0L
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mgr.registerGnssStatusCallback(appCtx.mainExecutor, cb)
            } else {
                mgr.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))
            }
            lm = mgr
            callback = cb
            Diag.log("GnssWatcher: đã đăng ký theo dõi vệ tinh")
        } catch (e: Exception) {
            Diag.log("GnssWatcher.start lỗi: ${e.message}")
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try { lm?.unregisterGnssStatusCallback(cb) } catch (e: Exception) { }
        lm = null
        lastUpdateAt = 0L
    }

    /**
     * Chuỗi ngắn để nhét vào dòng log/diag: `sat=<dùng được>/<nhìn thấy>`
     * (ví dụ `sat=8/21` = thấy 21 vệ tinh, dùng 8 để tính vị trí).
     *
     * `sat=?`  = chưa có dữ liệu (GNSS chưa chạy / máy không hỗ trợ).
     * `sat=0/0` = GNSS đang chạy mà KHÔNG thấy vệ tinh nào → nghi anten/GNSS hoặc bị che.
     * `sat=10/22(cũ)` = số liệu cũ hơn 2 phút, không còn tin được.
     */
    fun summary(): String {
        if (visible < 0) return "sat=?"
        if (lastUpdateAt == 0L ||
            SystemClock.elapsedRealtime() - lastUpdateAt > 120_000L
        ) return "sat=$used/$visible(cũ)"
        return "sat=$used/$visible"
    }

    /** Mô tả dài để hiện trên app (tiếng Việt). */
    fun describe(): String {
        if (visible < 0) return "chưa có dữ liệu (GNSS chưa chạy)"
        if (lastUpdateAt == 0L ||
            SystemClock.elapsedRealtime() - lastUpdateAt > 120_000L
        ) return "$used/$visible vệ tinh (số liệu cũ)"
        return "$used/$visible vệ tinh"
    }
}
