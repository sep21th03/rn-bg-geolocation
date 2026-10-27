package com.rnbggeo

/**
 * Máy trạng thái của geofence đa giác — THUẦN Kotlin, không Android, để test được.
 *
 * Tách khỏi [Polygons] nhờ vậy các chuyển trạng thái quan trọng (ENTER → DWELL →
 * EXIT, chống nhấp nháy ở biên, app restart) có test tự động thay vì phải đi bộ
 * thật để kiểm chứng.
 */
object PolygonLogic {

    const val NONE = 0
    const val ENTER = 1
    const val EXIT = 2
    const val DWELL = 4

    data class Decision(
        /** Sự kiện cần bắn: [ENTER], [EXIT], [DWELL] hoặc [NONE]. */
        val emit: Int,
        val inside: Boolean,
        /** Mốc bắt đầu vào vùng (ms). 0 = chưa từng vào. */
        val since: Long,
        val dwellFired: Boolean
    )

    /**
     * @param inside      mẫu hiện tại nằm trong đa giác
     * @param wasInside   trạng thái đã lưu trước đó
     * @param clearOfEdge đã ra khỏi biên xa hơn ngưỡng chống nhấp nháy
     * @param since       mốc vào vùng đã lưu (0 nếu chưa)
     * @param dwellFired  đã bắn DWELL cho lần vào này chưa
     * @param notifyDwell vùng có bật `notifyOnDwell`
     */
    fun decide(
        inside: Boolean,
        wasInside: Boolean,
        clearOfEdge: Boolean,
        since: Long,
        dwellFired: Boolean,
        now: Long,
        loiteringMs: Long,
        notifyDwell: Boolean
    ): Decision {
        // Vào vùng
        if (inside && !wasInside) {
            return Decision(ENTER, inside = true, since = now, dwellFired = false)
        }
        // Ra vùng — chỉ khi đã cách biên đủ xa, tránh GPS nhấp nháy quanh mép
        if (!inside && wasInside && clearOfEdge) {
            return Decision(EXIT, inside = false, since = now, dwellFired = false)
        }
        // Ở trong vùng
        if (inside) {
            val start = if (since <= 0L) now else since
            if (notifyDwell && !dwellFired && now - start >= loiteringMs) {
                return Decision(DWELL, inside = true, since = start, dwellFired = true)
            }
            return Decision(NONE, inside = true, since = start, dwellFired = dwellFired)
        }
        // Ngoài nhưng chưa đủ xa: giữ nguyên trạng thái (đang trong vùng)
        return Decision(NONE, inside = wasInside, since = since, dwellFired = dwellFired)
    }
}
