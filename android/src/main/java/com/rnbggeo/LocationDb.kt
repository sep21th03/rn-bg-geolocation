package com.rnbggeo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kho điểm GPS.
 *
 * Quan trọng: gửi server xong KHÔNG xoá bản ghi — chỉ đánh dấu `synced = 1`.
 * Nhờ vậy lịch sử hành trình vẫn còn để vẽ tuyến đường, còn hàng đợi offline
 * chỉ là các bản ghi `synced = 0`.
 */
class LocationDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appCtx: Context = ctx.applicationContext

    companion object {
        const val TABLE = "locations"
        private const val DB_NAME = "rn_bg_geo.db"
        private const val DB_VERSION = 2

        /** Trần số bản ghi giữ lại; vượt thì xoá dần các bản ghi cũ đã gửi. */
        private const val MAX_ROWS = 20_000

        @Volatile
        private var instance: LocationDb? = null

        /** 1 instance dùng chung — tránh 2 luồng cùng chạy onCreate (lỗi "table already exists"). */
        fun get(ctx: Context): LocationDb =
            instance ?: synchronized(this) {
                instance ?: LocationDb(ctx.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "uuid TEXT UNIQUE," +
                "timestamp INTEGER," +
                "event TEXT," +
                "is_moving INTEGER," +
                "activity TEXT," +
                "lat REAL," +
                "lng REAL," +
                "speed REAL," +
                "heading REAL," +
                "accuracy REAL," +
                "altitude REAL," +
                "battery REAL," +
                "odometer REAL," +
                "mock INTEGER," +
                "synced INTEGER NOT NULL DEFAULT 0," +
                "json TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_synced ON $TABLE (synced, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // DB tạo bởi bản 1 chưa có cột synced
            try {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
            } catch (e: Exception) {
                // đã có cột rồi thì bỏ qua
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_synced ON $TABLE (synced, id)")
        }
    }

    /** Đếm số lần insert để chỉ kiểm tra trần DB định kỳ, không COUNT mỗi điểm. */
    private var insertsSincePrune = 0

    /** @return true if inserted, false if duplicate uuid */
    fun insert(loc: JSONObject): Boolean {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("uuid", loc.optString("uuid"))
            put("timestamp", loc.optLong("timestamp"))
            put("event", loc.optString("event"))
            put("is_moving", if (loc.optBoolean("is_moving")) 1 else 0)
            val c = loc.optJSONObject("coords")
            val a = loc.optJSONObject("activity")
            put("activity", a?.optString("type") ?: "unknown")
            put("lat", c?.optDouble("latitude") ?: 0.0)
            put("lng", c?.optDouble("longitude") ?: 0.0)
            put("speed", c?.optDouble("speed") ?: 0.0)
            put("heading", c?.optDouble("heading") ?: 0.0)
            put("accuracy", c?.optDouble("accuracy") ?: 0.0)
            put("altitude", if (c?.has("altitude") == true) c.optDouble("altitude") else 0.0)
            put("battery", loc.optJSONObject("battery")?.optDouble("level") ?: 0.0)
            put("odometer", loc.optDouble("odometer"))
            put("mock", if (loc.optBoolean("mock")) 1 else 0)
            put("synced", 0)
            put("json", loc.toString())
        }
        val ok = try {
            db.insertOrThrow(TABLE, null, v) != -1L
        } catch (e: Exception) {
            false
        }
        if (ok) {
            insertsSincePrune++
            if (insertsSincePrune >= 100) {
                insertsSincePrune = 0
                prune()
                pruneByPolicy(appCtx)
            }
        }
        return ok
    }

    /**
     * Áp chính sách lưu trữ giống `persistence` của lib gốc:
     * - `maxDaysToPersist` > 0: xoá bản ghi ĐÃ GỬI cũ hơn N ngày.
     * - `maxRecordsToPersist` > 0: chỉ giữ N bản ghi mới nhất (ưu tiên xoá bản đã gửi).
     * Không bao giờ xoá bản ghi chưa gửi.
     */
    fun pruneByPolicy(ctx: Context) {
        val cfg = Prefs.config(ctx)
        val maxDays = cfg.optInt("maxDaysToPersist", -1)
        val maxRecords = cfg.optInt("maxRecordsToPersist", -1)
        try {
            if (maxDays > 0) {
                val cut = System.currentTimeMillis() - maxDays * 86_400_000L
                writableDatabase.delete(
                    TABLE, "timestamp < ? AND synced = 1", arrayOf(cut.toString())
                )
            }
            val n = total()
            if (maxRecords > 0 && n > maxRecords) {
                val over = n - maxRecords
                writableDatabase.execSQL(
                    "DELETE FROM $TABLE WHERE synced = 1 AND id IN (" +
                        "SELECT id FROM $TABLE WHERE synced = 1 ORDER BY id ASC LIMIT ?" +
                        ")",
                    arrayOf(over.toString())
                )
            }
        } catch (e: Exception) {
        }
    }

    /** Xoá một bản ghi theo uuid (giống destroyLocation của lib gốc). */
    fun deleteOne(uuid: String): Int =
        try { writableDatabase.delete(TABLE, "uuid = ?", arrayOf(uuid)) } catch (e: Exception) { 0 }

    /** Số bản ghi ĐANG CHỜ GỬI (dùng cho badge/notification). */
    fun count(): Int = scalar("SELECT COUNT(*) FROM $TABLE WHERE synced = 0")

    /** Tổng số bản ghi đang giữ (lịch sử hành trình). */
    fun total(): Int = scalar("SELECT COUNT(*) FROM $TABLE")

    private fun scalar(sql: String): Int {
        readableDatabase.rawQuery(sql, null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** oldest-first pending records */
    fun pending(limit: Int): JSONArray = query(
        "SELECT json FROM $TABLE WHERE synced = 0 ORDER BY id ASC LIMIT ?",
        arrayOf(limit.toString())
    )

    /** newest-first history for UI */
    fun recent(limit: Int): JSONArray = query(
        "SELECT json FROM $TABLE ORDER BY id DESC LIMIT ?",
        arrayOf(limit.toString())
    )

    /** oldest-first toàn bộ lịch sử — để vẽ tuyến đường theo đúng thứ tự thời gian */
    fun history(limit: Int): JSONArray = query(
        // Lấy N bản ghi MỚI NHẤT rồi mới sắp tăng dần: nếu LIMIT + ORDER BY id ASC
        // thì khi DB vượt N bản ghi, app chỉ thấy dữ liệu CŨ và tưởng "mất dữ liệu".
        "SELECT json FROM (SELECT id, json FROM $TABLE ORDER BY id DESC LIMIT ?) ORDER BY id ASC",
        arrayOf(limit.toString())
    )

    private fun query(sql: String, args: Array<String>): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                try { arr.put(JSONObject(c.getString(0))) } catch (e: Exception) {}
            }
        }
        return arr
    }

    /** Đánh dấu đã gửi thành công (KHÔNG xoá — giữ lại để vẽ hành trình). */
    fun markSynced(uuids: List<String>): Int {
        if (uuids.isEmpty()) return 0
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            uuids.forEach {
                n += db.compileStatement("UPDATE $TABLE SET synced = 1 WHERE uuid = ?").use { st ->
                    st.bindString(1, it)
                    st.executeUpdateDelete()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** Xoá các bản ghi cũ ĐÃ GỬI khi vượt trần, để DB không phình vô hạn. */
    private fun prune() {
        val n = total()
        if (n <= MAX_ROWS) return
        try {
            writableDatabase.execSQL(
                "DELETE FROM $TABLE WHERE synced = 1 AND id IN (" +
                    "SELECT id FROM $TABLE WHERE synced = 1 ORDER BY id ASC LIMIT ?" +
                    ")",
                arrayOf((n - MAX_ROWS).toString())
            )
        } catch (e: Exception) {
        }
    }

    fun delete(uuids: List<String>): Int {
        if (uuids.isEmpty()) return 0
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            uuids.forEach { n += db.delete(TABLE, "uuid = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun destroy() {
        writableDatabase.delete(TABLE, null, null)
        writableDatabase.execSQL("VACUUM")
    }
}
