package com.rnbggeo

import android.location.Location
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.pow

/**
 * Bộ lọc mẫu thô — tương đương `LocationFilter` của transistorsoft.
 *
 * Vì sao cần: GPS thô luôn có lúc "nhảy". Một mẫu sai 100 m giữa hai mẫu đúng sẽ tạo ra
 * một cái gai trên bản đồ và cộng thêm 200 m vào quãng đường. Bộ lọc chặn mẫu xấu
 * **trước khi** nó được ghi vào SQLite, cộng vào odometer, hay bắn `onLocation`.
 *
 * ## Vì sao phải giữ lại một mẫu (look-ahead)
 *
 * Cách làm ngây thơ — so mẫu mới với mẫu trước, thấy tốc độ vô lý thì loại mẫu mới — bắt
 * nhầm đúng chỗ then chốt. Với một cái gai A → S → B (S lệch xa, B quay về gần A):
 *   • A→S có thể trông hợp lý nếu hai mẫu cách nhau vài chục giây → S được nhận.
 *   • S→B thì vô lý → B bị loại.
 * Kết quả: giữ lại đúng cái gai và ném đi điểm thật.
 *
 * Nên bộ lọc giữ mẫu mới nhất lại một nhịp, chờ mẫu kế tiếp rồi mới phán:
 * nếu **cả hai chặng** đều vô lý và mẫu kế tiếp quay về gần mốc cũ → mẫu đang giữ là gai.
 * Đổi lại, dữ liệu trễ đúng một mẫu (một nhịp GPS). [flush] để đẩy nốt mẫu cuối khi dừng.
 *
 * ## Cấu hình (`config.filter`)
 * ```
 * filter: {
 *   policy: "adjust" | "passThrough",
 *   useKalman: true,
 *   trackingAccuracyThreshold: 100,   // m — sai số ngang tối đa để nhận
 *   maxImpliedSpeed: 60,              // m/s — nhanh hơn coi là vô lý
 *   odometerAccuracyThreshold: 20     // m — sai số tối đa để được cộng quãng đường
 * }
 * ```
 */
class LocationFilter(config: JSONObject) {

    enum class Policy { ADJUST, PASS_THROUGH }

    data class Result(
        val accepted: Boolean,
        val reason: String,
        val location: Location?,
        val odometerDelta: Double,
        val rawDistance: Double,
        val smoothed: Boolean,
        /** Mẫu thô ban đầu — cần cho việc ghi log/event khi bị loại. */
        val raw: Location? = null,
    )

    private val policy: Policy
    private val useKalman: Boolean
    private val accThreshold: Double
    private val maxImpliedSpeed: Double
    private val odoAccThreshold: Double
    private val allowIdentical: Boolean

    /** Điểm gốc hệ ENU — chốt ở mẫu đầu tiên. */
    private var originLat = Double.NaN
    private var originLng = Double.NaN
    private val kfEast = Kalman1D()
    private val kfNorth = Kalman1D()

    /** Mốc so sánh: mẫu ĐÃ ĐƯỢC NHẬN gần nhất (toạ độ thô). */
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var lastTime = 0L

    /** Toạ độ sau làm mượt của mẫu được nhận gần nhất — mốc cộng odometer. */
    private var lastOutLat = Double.NaN
    private var lastOutLng = Double.NaN

    /** Mẫu đang giữ, chờ mẫu kế tiếp để phán. */
    private var pending: Location? = null

    var rejected = 0
        private set
    var accepted = 0
        private set

    init {
        val f = config.optJSONObject("filter") ?: JSONObject()
        policy = when (f.optString("policy", "adjust").lowercase()) {
            "passthrough", "pass_through", "pass-through" -> Policy.PASS_THROUGH
            else -> Policy.ADJUST
        }
        useKalman = f.optBoolean("useKalman", true) && policy == Policy.ADJUST
        accThreshold = f.optDouble("trackingAccuracyThreshold", 100.0)
        maxImpliedSpeed = f.optDouble("maxImpliedSpeed", 60.0)
        odoAccThreshold = f.optDouble("odometerAccuracyThreshold", 20.0)
        allowIdentical = config.optBoolean("allowIdenticalLocations", false)
    }

    fun summary(): String =
        "policy=${policy.name.lowercase()} kalman=$useKalman acc<=${accThreshold.toInt()}m " +
            "vmax=${maxImpliedSpeed.toInt()}m/s odoAcc<=${odoAccThreshold.toInt()}m identical=$allowIdentical"

    fun reset() {
        originLat = Double.NaN
        originLng = Double.NaN
        kfEast.reset()
        kfNorth.reset()
        lastLat = Double.NaN
        lastLng = Double.NaN
        lastTime = 0L
        lastOutLat = Double.NaN
        lastOutLng = Double.NaN
        pending = null
    }

    /**
     * Nạp một mẫu.
     * @return kết quả cho mẫu ĐÃ QUYẾT ĐỊNH (có thể là mẫu của lần gọi trước),
     *         hoặc `null` nếu mẫu vừa nạp đang được giữ lại chờ mẫu kế tiếp.
     */
    fun feed(loc: Location): Result? {
        val held = pending
        if (held == null) {
            pending = loc
            return null
        }

        val res = evaluate(held, loc)
        if (res.accepted) {
            pending = loc
            return accept(held, res.rawDistance).copy(raw = held)
        }

        // mẫu đang giữ là điểm nhảy → bỏ nó, thử mẫu vừa tới với mốc trước
        rejected++
        pending = null
        val res2 = evaluate(loc, null)
        if (res2.accepted) {
            return accept(loc, res2.rawDistance).copy(raw = loc)
        }
        rejected++
        return res2.copy(raw = loc)
    }

    /** Đẩy nốt mẫu đang giữ (gọi khi dừng theo dõi hoặc chuyển sang đứng yên). */
    fun flush(): Result? {
        val held = pending ?: return null
        pending = null
        val res = evaluate(held, null)
        if (res.accepted) return accept(held, res.rawDistance).copy(raw = held)
        rejected++
        return res.copy(raw = held)
    }

    // ------------------------------------------------------------ phán đoán
    private fun evaluate(cand: Location, next: Location?): Result {
        if (cand.accuracy > accThreshold) {
            return Result(false, "accuracy", null, 0.0, 0.0, false)
        }

        val lat = cand.latitude
        val lng = cand.longitude
        if (lastLat.isNaN()) {
            return Result(true, "", cand, 0.0, 0.0, false)
        }

        val dIn = Util.haversine(lastLat, lastLng, lat, lng)
        val dtIn = (cand.time - lastTime) / 1000.0

        if (dIn < 0.5 && !allowIdentical) {
            return Result(false, "identical", null, 0.0, dIn, false)
        }

        val speedIn = when {
            dtIn > 0.001 -> dIn / dtIn
            dIn > 50.0 -> Double.MAX_VALUE // cùng thời điểm mà lệch xa
            else -> 0.0
        }
        if (speedIn > maxImpliedSpeed) {
            return Result(false, "speed", null, 0.0, dIn, false)
        }

        // Gai một điểm: chặng tới cũng vô lý VÀ mẫu kế tiếp quay về gần mốc cũ.
        if (next != null) {
            val dOut = Util.haversine(lat, lng, next.latitude, next.longitude)
            val dtOut = (next.time - cand.time) / 1000.0
            val speedOut = if (dtOut > 0.001) dOut / dtOut else if (dOut > 50.0) Double.MAX_VALUE else 0.0
            if (speedOut > maxImpliedSpeed) {
                val back = Util.haversine(lastLat, lastLng, next.latitude, next.longitude)
                if (back < dIn * 0.5) {
                    return Result(false, "speed", null, 0.0, dIn, false)
                }
            }
        }

        return Result(true, "", cand, 0.0, dIn, false)
    }

    // ------------------------------------------------------------ chấp nhận
    private fun accept(raw: Location, rawDistance: Double): Result {
        var outLat = raw.latitude
        var outLng = raw.longitude
        var smoothed = false

        if (useKalman) {
            if (originLat.isNaN()) {
                originLat = outLat
                originLng = outLng
            }
            val (e, n) = toEnu(outLat, outLng)
            val rVar = raw.accuracy.coerceAtLeast(1f).toDouble().pow(2)
            val q = 0.6
            val dt = if (lastTime > 0) ((raw.time - lastTime) / 1000.0).coerceIn(0.2, 30.0) else 1.0
            val se = kfEast.step(dt, e, rVar, q)
            val sn = kfNorth.step(dt, n, rVar, q)
            val (flat, flng) = fromEnu(se, sn)
            // Kalman trễ pha; lệch quá xa mẫu thô thì bám theo mẫu thô để không kéo lê tuyến đường
            val lag = Util.haversine(flat, flng, outLat, outLng)
            if (lag > 25.0) {
                kfEast.reset(e, kfEast.vel, rVar, 25.0)
                kfNorth.reset(n, kfNorth.vel, rVar, 25.0)
            } else {
                outLat = flat
                outLng = flng
                smoothed = lag > 0.3
            }
        }

        var delta = 0.0
        if (!lastOutLat.isNaN() && raw.accuracy <= odoAccThreshold) {
            delta = Util.haversine(lastOutLat, lastOutLng, outLat, outLng)
            if (delta > 5000.0) delta = 0.0 // mất dấu, không cộng
        }

        lastLat = raw.latitude
        lastLng = raw.longitude
        lastTime = raw.time
        lastOutLat = outLat
        lastOutLng = outLng
        accepted++

        val out = Location(raw).apply {
            latitude = outLat
            longitude = outLng
        }
        return Result(true, "", out, delta, rawDistance, smoothed)
    }

    // ------------------------------------------------------------ ENU
    private fun toEnu(lat: Double, lng: Double): Pair<Double, Double> {
        val r = Math.PI / 180.0
        val mPerDegLat = 6_371_000.0 * r
        val mPerDegLng = mPerDegLat * cos(originLat * r)
        return (lng - originLng) * mPerDegLng to (lat - originLat) * mPerDegLat
    }

    private fun fromEnu(east: Double, north: Double): Pair<Double, Double> {
        val r = Math.PI / 180.0
        val mPerDegLat = 6_371_000.0 * r
        val mPerDegLng = mPerDegLat * cos(originLat * r)
        return (originLat + north / mPerDegLat) to (originLng + east / mPerDegLng)
    }

    /** Kalman 1 chiều, mô hình vận tốc không đổi. Trạng thái [vị trí, vận tốc]. */
    private class Kalman1D {
        var pos = 0.0
            private set
        var vel = 0.0
            private set
        private var p00 = 0.0
        private var p01 = 0.0
        private var p10 = 0.0
        private var p11 = 0.0
        private var ready = false

        fun reset() {
            ready = false
            pos = 0.0; vel = 0.0
            p00 = 0.0; p01 = 0.0; p10 = 0.0; p11 = 0.0
        }

        fun reset(p: Double, v: Double, posVar: Double, velVar: Double) {
            pos = p; vel = v
            p00 = posVar; p01 = 0.0; p10 = 0.0; p11 = velVar
            ready = true
        }

        fun step(dt: Double, meas: Double, measVar: Double, q: Double): Double {
            if (!ready) {
                reset(meas, 0.0, measVar, 100.0)
                return meas
            }
            pos += vel * dt
            val n00 = p00 + dt * (p10 + p01) + dt * dt * p11 + q * dt * dt * dt * dt / 4.0
            val n01 = p01 + dt * p11 + q * dt * dt * dt / 2.0
            val n10 = p10 + dt * p11 + q * dt * dt * dt / 2.0
            val n11 = p11 + q * dt * dt
            p00 = n00; p01 = n01; p10 = n10; p11 = n11

            val s = p00 + measVar
            if (s <= 0.0) return pos
            val k0 = p00 / s
            val k1 = p10 / s
            val innov = meas - pos
            pos += k0 * innov
            vel += k1 * innov

            val u00 = (1 - k0) * p00
            val u01 = (1 - k0) * p01
            val u10 = p10 - k1 * p00
            val u11 = p11 - k1 * p01
            p00 = u00; p01 = u01; p10 = u10; p11 = u11
            return pos
        }
    }
}
