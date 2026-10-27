//
//  BgGeoDb.swift
//  Kho điểm GPS cho iOS — cùng schema với Android để cùng định dạng JSON.
//  Gửi server xong KHÔNG xoá bản ghi, chỉ đánh dấu synced = 1.
//

import Foundation
import SQLite3

final class BgGeoDb {

    static let shared = BgGeoDb()

    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "rnbggeo.db")
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// Trần số bản ghi giữ lại (giống Android MAX_ROWS).
    private let maxRows = 20_000

    private init() {
        let fm = FileManager.default
        let dir = fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("rnbggeo", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("rn_bg_geo.db").path
        guard sqlite3_open(path, &db) == SQLITE_OK else {
            NSLog("[BgGeo] khong mo duoc SQLite")
            return
        }
        exec("""
        CREATE TABLE IF NOT EXISTS locations (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          uuid TEXT UNIQUE,
          timestamp INTEGER,
          lat REAL, lng REAL, speed REAL, heading REAL, accuracy REAL, altitude REAL,
          battery REAL, odometer REAL, mock INTEGER,
          synced INTEGER NOT NULL DEFAULT 0,
          json TEXT)
        """)
        exec("CREATE INDEX IF NOT EXISTS idx_locations_synced ON locations (synced, id)")
    }

    private func exec(_ sql: String) {
        var err: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &err) != SQLITE_OK, let e = err {
            NSLog("[BgGeo] SQL error: %@", String(cString: e))
            sqlite3_free(err)
        }
    }

    private func scalar(_ sql: String) -> Int {
        var st: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(st) }
        return sqlite3_step(st) == SQLITE_ROW ? Int(sqlite3_column_int64(st, 0)) : 0
    }

    private func num(_ v: Any?) -> Double {
        (v as? NSNumber)?.doubleValue ?? 0
    }

    @discardableResult
    func insert(_ loc: [String: Any]) -> Bool {
        var ok = false
        queue.sync {
            let json = (try? JSONSerialization.data(withJSONObject: loc))
                .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
            let coords = loc["coords"] as? [String: Any] ?? [:]
            let uuid = loc["uuid"] as? String ?? UUID().uuidString

            let sql = """
            INSERT OR IGNORE INTO locations
              (uuid, timestamp, lat, lng, speed, heading, accuracy, altitude,
               battery, odometer, mock, synced, json)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?)
            """
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(st) }

            sqlite3_bind_text(st, 1, uuid, -1, transient)
            sqlite3_bind_int64(st, 2, (loc["timestamp"] as? NSNumber)?.int64Value ?? 0)
            sqlite3_bind_double(st, 3, num(coords["latitude"]))
            sqlite3_bind_double(st, 4, num(coords["longitude"]))
            sqlite3_bind_double(st, 5, num(coords["speed"]))
            sqlite3_bind_double(st, 6, num(coords["heading"]))
            sqlite3_bind_double(st, 7, num(coords["accuracy"]))
            sqlite3_bind_double(st, 8, num(coords["altitude"]))
            sqlite3_bind_double(st, 9, num(loc["battery"]))
            sqlite3_bind_double(st, 10, num(loc["odometer"]))
            sqlite3_bind_int(st, 11, (loc["mock"] as? Bool ?? false) ? 1 : 0)
            sqlite3_bind_text(st, 12, json, -1, transient)
            ok = sqlite3_step(st) == SQLITE_DONE && sqlite3_changes(db) > 0
            if ok { pruneIfNeeded() }
        }
        return ok
    }

    /// Các bản ghi CHƯA gửi, cũ → mới. Trả về (uuid, json).
    func pending(limit: Int) -> [(String, String)] {
        var out: [(String, String)] = []
        queue.sync {
            let sql = "SELECT uuid, json FROM locations WHERE synced = 0 ORDER BY id ASC LIMIT ?"
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(st) }
            sqlite3_bind_int(st, 1, Int32(limit))
            while sqlite3_step(st) == SQLITE_ROW {
                out.append((String(cString: sqlite3_column_text(st, 0)),
                            String(cString: sqlite3_column_text(st, 1))))
            }
        }
        return out
    }

    /// N bản ghi MỚI NHẤT nhưng trả về cũ → mới (giống fix bên Android).
    func history(limit: Int) -> [String] {
        var out: [String] = []
        queue.sync {
            let sql = "SELECT json FROM (SELECT id, json FROM locations ORDER BY id DESC LIMIT ?) ORDER BY id ASC"
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(st) }
            sqlite3_bind_int(st, 1, Int32(limit))
            while sqlite3_step(st) == SQLITE_ROW {
                out.append(String(cString: sqlite3_column_text(st, 0)))
            }
        }
        return out
    }

    func markSynced(_ uuids: [String]) {
        guard !uuids.isEmpty else { return }
        queue.sync {
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "UPDATE locations SET synced = 1 WHERE uuid = ?", -1, &st, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(st) }
            for uuid in uuids {
                sqlite3_bind_text(st, 1, uuid, -1, transient)
                _ = sqlite3_step(st)
                sqlite3_reset(st)
            }
        }
    }

    /// Đếm bản ghi — BẮT BUỘC đi qua `queue`: handle sqlite3 dùng chung, nếu gọi thẳng
    /// từ luồng khác (TurboModule queue) trong lúc queue `rnbggeo.db` đang ghi/đọc thì
    /// sqlite3_prepare_v2/step đọc phải bộ nhớ đã bị thay đổi → crash EXC_BAD_ACCESS.
    func pendingCount() -> Int {
        queue.sync { scalar("SELECT COUNT(*) FROM locations WHERE synced = 0") }
    }

    func total() -> Int {
        queue.sync { scalar("SELECT COUNT(*) FROM locations") }
    }

    func deleteAll() {
        queue.sync { exec("DELETE FROM locations") }
    }

    @discardableResult
    func deleteOne(_ uuid: String) -> Int {
        var n = 0
        queue.sync {
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, "DELETE FROM locations WHERE uuid = ?", -1, &st, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(st) }
            sqlite3_bind_text(st, 1, uuid, -1, transient)
            _ = sqlite3_step(st)
            n = Int(sqlite3_changes(db))
        }
        return n
    }

    /// Chính sách lưu trữ — TRƯỚC ĐÂY iOS không có, trong khi Android đã có
    /// (LocationDb.trim) và index.js/types.d.ts vẫn quảng cáo 2 khoá này.
    /// - maxDays > 0: xoá bản ghi ĐÃ GỬI cũ hơn N ngày
    /// - maxRecords > 0: chỉ giữ N bản ghi mới nhất, ưu tiên xoá bản đã gửi
    /// Không bao giờ xoá bản chưa gửi (synced = 0) trừ khi buộc phải cắt theo maxRecords.
    func trim(maxDays: Int, maxRecords: Int) {
        queue.sync {
            if maxDays > 0 {
                let cutoff = Int(Date().timeIntervalSince1970 * 1000) - maxDays * 86_400_000
                exec("DELETE FROM locations WHERE synced = 1 AND timestamp < \(cutoff)")
            }
            if maxRecords > 0 {
                let n = scalar("SELECT COUNT(*) FROM locations")
                guard n > maxRecords else { return }
                // (synced = 0) ASC ⇒ bản đã gửi (0) bị xoá trước, giữ bản còn chờ gửi.
                exec("""
                DELETE FROM locations WHERE id IN (
                  SELECT id FROM locations ORDER BY (synced = 0) ASC, id ASC LIMIT \(n - maxRecords))
                """)
            }
        }
    }

    /// Chỉ xoá bản ghi ĐÃ GỬI khi vượt trần — không bao giờ xoá bản chưa gửi.
    /// Hàm này LUÔN chạy bên trong `queue` (từ insert) nên dùng `scalar` trực tiếp,
    /// không gọi `total()` (total() có queue.sync → sẽ deadlock).
    private func pruneIfNeeded() {
        let n = scalar("SELECT COUNT(*) FROM locations")
        guard n > maxRows else { return }
        exec("""
        DELETE FROM locations WHERE synced = 1 AND id IN (
          SELECT id FROM locations WHERE synced = 1 ORDER BY id ASC LIMIT \(n - maxRows))
        """)
    }
}
