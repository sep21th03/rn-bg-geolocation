//
//  BgGeoUploader.swift
//  Gửi điểm lên server bằng URLSession — HOÀN TOÀN BẤT ĐỒNG BỘ.
//  (Bản đầu dùng DispatchSemaphore để chờ POST: nó chặn queue và làm app abort.)
//

import Foundation

final class BgGeoUploader {

    static let shared = BgGeoUploader()

    private let db = BgGeoDb.shared
    private let session = URLSession(configuration: .default)
    private let queue = DispatchQueue(label: "rnbggeo.upload")
    private var running = false

    /// Gọi khi có kết quả HTTP để module bắn event `rnbggeo:http`.
    var onResult: (([String: Any]) -> Void)?
    /// Gọi trước mỗi lần POST để ghi log hình dạng body (chẩn đoán hợp đồng API).
    var onBody: ((String) -> Void)?

    private func main(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }

    /// Gửi toàn bộ điểm đang chờ. Completion luôn chạy trên main.
    func sync(config: [String: Any], completion: @escaping (Bool) -> Void) {
        let urlString = (config["url"] as? String) ?? ""
        guard let url = URL(string: urlString), !urlString.isEmpty else {
            completion(false)
            return
        }
        queue.async { [weak self] in
            guard let self = self else { return }
            if self.running {
                self.main { completion(false) }
                return
            }
            self.running = true

            let batch = (config["batchSync"] as? Bool) ?? false
            let maxBatch = (config["maxBatchSize"] as? NSNumber)?.intValue ?? 500
            let rows = self.db.pending(limit: maxBatch > 0 ? maxBatch : 500)
            guard !rows.isEmpty else {
                self.running = false
                self.main { completion(true) }
                return
            }

            var headers: [String: String] = ["Content-Type": "application/json"]
            if let h = config["headers"] as? [String: String] {
                h.forEach { headers[$0.key] = $0.value }
            }
            // Giống Android/HttpUploader: `httpRootProperty` + `params` quyết định hình dạng
            // body. Trước 22/10 iOS gửi dict trần nên backend viết cho Android (đọc
            // body.location) nhận sai — hai nền tảng lệch hợp đồng.
            let root = (config["httpRootProperty"] as? String) ?? "location"
            let extra = (config["params"] as? [String: Any]) ?? [:]

            if batch {
                let objs = rows.compactMap { row -> [String: Any]? in
                    guard let d = row.1.data(using: .utf8),
                          let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any]
                    else { return nil }
                    return o
                }
                let body = self.wrapBody(objs, root: root, extra: extra)
                self.post(url: url, headers: headers, body: body) { ok in
                    if ok { self.db.markSynced(rows.map { $0.0 }) }
                    self.running = false
                    self.main { completion(ok) }
                }
            } else {
                self.sendSequentially(rows, at: 0, url: url, headers: headers,
                                      root: root, extra: extra) { ok in
                    self.running = false
                    self.main { completion(ok) }
                }
            }
        }
    }

    /// Dựng body theo `httpRootProperty` + `params` (giống Android).
    /// root rỗng hoặc "." → dùng "batch"/"location" như Android.
    private func wrapBody(_ payload: Any, root: String, extra: [String: Any]) -> String {
        let fallback = (payload is [Any]) ? "batch" : "location"
        let key = (root.isEmpty || root == ".") ? fallback : root
        var body: [String: Any] = [key: payload]
        extra.forEach { body[$0.key] = $0.value }
        guard JSONSerialization.isValidJSONObject(body),
              let d = try? JSONSerialization.data(withJSONObject: body) else { return "{}" }
        return String(data: d, encoding: .utf8) ?? "{}"
    }

    private func sendSequentially(_ rows: [(String, String)], at index: Int,
                                  url: URL, headers: [String: String],
                                  root: String, extra: [String: Any],
                                  done: @escaping (Bool) -> Void) {
        guard index < rows.count else { done(true); return }
        let (uuid, json) = rows[index]
        let obj = (json.data(using: .utf8)).flatMap {
            try? JSONSerialization.jsonObject(with: $0) as? [String: Any]
        } ?? [:]
        let body = wrapBody(obj, root: root, extra: extra)
        post(url: url, headers: headers, body: body) { ok in
            guard ok else {
                done(false) // mạng hỏng: giữ hàng đợi, lần sau gửi tiếp
                return
            }
            self.db.markSynced([uuid])
            self.sendSequentially(rows, at: index + 1, url: url, headers: headers,
                                  root: root, extra: extra, done: done)
        }
    }

    private func post(url: URL, headers: [String: String], body: String,
                      done: @escaping (Bool) -> Void) {
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 30
        headers.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
        req.httpBody = body.data(using: .utf8)
        // In hình dạng body (160 ký tự đầu) để đối chiếu hợp đồng với backend.
        onBody?(body)

        session.dataTask(with: req) { [weak self] data, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let ok = error == nil && (200...299).contains(status)
            let text = data.flatMap { String(data: $0, encoding: .utf8) }
                ?? error?.localizedDescription ?? ""
            self?.onResult?(["success": ok, "status": status, "response": text])
            done(ok)
        }.resume()
    }

    /// Gửi một sự kiện (vd geofence ENTER/EXIT) — không đụng tới hàng đợi điểm GPS.
    func postEvent(url: URL, headers: [String: String], json: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(json),
              let data = try? JSONSerialization.data(withJSONObject: json) else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 30
        headers.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
        req.httpBody = data
        session.dataTask(with: req) { [weak self] body, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let ok = error == nil && (200...299).contains(status)
            let text = body.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            self?.onResult?(["success": ok, "status": status, "response": text])
        }.resume()
    }

    /// Như `postEvent` nhưng trả kết quả về cho caller (dùng cho `uploadLog`, cần biết
    /// mã HTTP để báo lại cho JS). Completion chạy trên queue của URLSession — caller tự
    /// chuyển về main nếu cần.
    func postEventWithResult(url: URL, headers: [String: String], json: [String: Any],
                             done: @escaping (Bool, Int, String) -> Void) {
        guard JSONSerialization.isValidJSONObject(json),
              let data = try? JSONSerialization.data(withJSONObject: json) else {
            done(false, 0, "body không hợp lệ")
            return
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 30
        headers.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
        req.httpBody = data
        session.dataTask(with: req) { [weak self] body, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let ok = error == nil && (200...299).contains(status)
            let text = body.flatMap { String(data: $0, encoding: .utf8) }
                ?? error?.localizedDescription ?? ""
            self?.onResult?(["success": ok, "status": status, "response": text])
            done(ok, status, text)
        }.resume()
    }
}
