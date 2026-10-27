//
//  BgGeoGeofences.swift
//  Lưu định nghĩa geofence + đăng ký CLCircularRegion (iOS giới hạn 20 vùng/app).
//

import Foundation
import CoreLocation

final class BgGeoGeofences {

    static let shared = BgGeoGeofences()

    /**
     Bán kính TỐI THIỂU để iOS theo dõi vùng ở tầng OS (CLCircularRegion).

     Vì sao vẫn phải có: Apple ghi trong `CLCircularRegion.init(center:radius:identifier:)`
     — "the location manager doesn't generate notifications immediately upon crossing a
     region boundary. Instead, it applies time and distance criteria…". Vùng vài mét vì
     thế gần như không bao giờ được OS báo.

     QUAN TRỌNG: đây chỉ là bán kính ĐÁNH THỨC. Bán kính người dùng chọn được LƯU ĐÚNG
     NGUYÊN (10 m là 10 m) và ENTER/EXIT thật do `BgGeo.evaluateCircles()` chấm trên từng
     mẫu GPS. Trước 22/10/2026 code lưu `max(radius, 100)` → chọn 10 m lại ra 100 m.
     */
    static let minNativeRadius: Double = 100

    /// Bán kính dùng khi đăng ký với CLLocationManager (≥ 100 m).
    static func nativeRadius(_ radius: Double) -> Double {
        max(radius, minNativeRadius)
    }

    private let key = "rnbggeo_geofences"
    private let defaults = UserDefaults.standard
    private var cache: [[String: Any]]?
    /**
     Danh sách vừa thay đổi → cần đăng ký lại với CLLocationManager.

     VÌ SAO PHẢI CÓ CỜ NÀY: trước đây `syncRegions()` được gọi ở CUỐI MỖI mẫu GPS
     (BgGeo.handle). Mỗi lần gọi nó `stopMonitoring` rồi `startMonitoring` lại toàn bộ
     vùng. Hệ quả: lúc đi vào vùng, iOS không thấy "vượt biên" nào (vùng vừa bị huỷ rồi
     tạo lại) nên KHÔNG bắn `didEnterRegion` → không có sự kiện ENTER, không có thông
     báo. Đo 22/10: vào Cty 07:34:57 mà /geofences không có bản ghi nào.
     */
    private(set) var needsSync = true
    /// `all()` được gọi từ TurboModule queue (state()) trong khi UI/native có thể đang
    /// add/remove → phải khoá để không đọc phải mảng đang bị thay đổi.
    private let lock = NSLock()

    private init() {}

    private func load() -> [[String: Any]] {
        lock.lock(); defer { lock.unlock() }
        if let c = cache { return c }
        let arr = (defaults.array(forKey: key) as? [[String: Any]]) ?? []
        cache = arr
        return arr
    }

    private func save(_ arr: [[String: Any]]) {
        lock.lock(); defer { lock.unlock() }
        cache = arr
        defaults.set(arr, forKey: key)
    }

    func all() -> [[String: Any]] { load() }

    func one(_ identifier: String) -> [String: Any]? {
        load().first { ($0["identifier"] as? String) == identifier }
    }

    /// Chuẩn hoá: identifier bắt buộc; hỗ trợ VÒNG TRÒN (lat/lng/radius) và ĐA GIÁC (vertices).
    @discardableResult
    func add(_ g: [String: Any]) -> Bool {
        var dict = g
        let id = (dict["identifier"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let identifier = id, !identifier.isEmpty else { return false }

        // --- Đa giác: iOS không có vùng đa giác, nên lưu đỉnh + đăng ký một vòng
        // bao quanh để hệ thống đánh thức app; việc ENTER/EXIT do point-in-polygon
        // tính trên từng mẫu GPS (xem BgGeoGeofences.polygonEvent).
        if let rawVerts = dict["vertices"] as? [[Double]], rawVerts.count >= 3 {
            let verts = rawVerts.filter { $0.count >= 2 }
            guard verts.count >= 3 else { return false }
            let lats = verts.map { $0[0] }
            let lngs = verts.map { $0[1] }
            let minLat = lats.min()!, maxLat = lats.max()!
            let minLng = lngs.min()!, maxLng = lngs.max()!
            let cLat = (minLat + maxLat) / 2
            let cLng = (minLng + maxLng) / 2
            // Bán kính bao: xa nhất từ tâm hộp tới một đỉnh (+50 m dư).
            var maxD: Double = 0
            for v in verts {
                let dLat = (v[0] - cLat) * 111_320
                let dLng = (v[1] - cLng) * 111_320 * cos(cLat * .pi / 180)
                maxD = max(maxD, (dLat * dLat + dLng * dLng).squareRoot())
            }
            dict["identifier"] = identifier
            dict["vertices"] = verts
            dict["latitude"] = cLat
            dict["longitude"] = cLng
            dict["radius"] = min(max(maxD + 50, 100), 100_000)
            dict["polygon"] = true
        } else {
            let lat = (dict["latitude"] as? NSNumber)?.doubleValue
                ?? (dict["coords"] as? [String: Any]).flatMap { ($0["latitude"] as? NSNumber)?.doubleValue }
            let lng = (dict["longitude"] as? NSNumber)?.doubleValue
                ?? (dict["coords"] as? [String: Any]).flatMap { ($0["longitude"] as? NSNumber)?.doubleValue }
            guard let latitude = lat, let longitude = lng else { return false }
            let radius = (dict["radius"] as? NSNumber)?.doubleValue ?? 1000
            dict["identifier"] = identifier
            dict["latitude"] = latitude
            dict["longitude"] = longitude
            // LƯU ĐÚNG bán kính người dùng chọn (10 m = 10 m). Chỉ khi đăng ký với iOS
            // mới nâng lên tối thiểu 100 m (nativeRadius) — xem chú thích minNativeRadius.
            dict["radius"] = min(max(radius, 1), 100_000)
            dict["vertices"] = nil
            dict["polygon"] = false
        }

        var arr = load().filter { ($0["identifier"] as? String) != identifier }
        arr.append(dict)
        save(arr)
        needsSync = true
        // Ghi lại bán kính ĐÃ LƯU và bán kính ĐÁNH THỨC — để lần sau chỉ cần đọc log là biết
        // "chọn 10 m có bị đổi thành 100 m không" (lỗi 22/10: lưu max(r,100)).
        if let r = (dict["radius"] as? NSNumber)?.doubleValue {
            BgGeo.shared?.log(String(format: "geofence: thêm %@ · bán kính lưu %.0f m · đánh thức %.0f m",
                                     (dict["name"] as? String) ?? identifier, r,
                                     BgGeoGeofences.nativeRadius(r)))
        }
        return true
    }

    /**
     * Ray casting: điểm có nằm trong đa giác không.
     * Đa giác lưu dạng [[lat, lng], ...] (cùng thứ tự với Android).
     */
    static func pointInPolygon(_ lat: Double, _ lng: Double, _ verts: [[Double]]) -> Bool {
        var inside = false
        var j = verts.count - 1
        for i in 0..<verts.count {
            let yi = verts[i][0], xi = verts[i][1]
            let yj = verts[j][0], xj = verts[j][1]
            if ((yi > lat) != (yj > lat)) &&
                (lng < (xj - xi) * (lat - yi) / ((yj - yi) == 0 ? 1e-12 : (yj - yi)) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    @discardableResult
    func remove(_ ids: [String]) -> Bool {
        var arr = load()
        if ids.isEmpty {
            arr = []
        } else {
            arr.removeAll { ids.contains(($0["identifier"] as? String) ?? "") }
        }
        save(arr)
        needsSync = true
        return true
    }

    /// Đăng ký lại toàn bộ vùng đang giám sát (tối đa 20).
    func syncRegions(with manager: CLLocationManager) {
        // Giữ lại geofence "vị trí cuối" (nếu đang có) — nó không nằm trong danh sách
        // geofence của người dùng, xoá nhầm là mất cơ chế đánh thức khi app ngủ/bị kill.
        manager.monitoredRegions
            .filter { $0.identifier != BgGeo.lastKnownRegionId }
            .forEach { manager.stopMonitoring(for: $0) }
        // Chừa 1 slot cho geofence "vị trí cuối" (iOS giới hạn 20 region/app).
        let limit = max(BgGeo.maxGeofences - 1, 1)
        let list = Array(load().prefix(limit))
        for g in list {
            guard let id = g["identifier"] as? String,
                  let lat = (g["latitude"] as? NSNumber)?.doubleValue,
                  let lng = (g["longitude"] as? NSNumber)?.doubleValue,
                  let radius = (g["radius"] as? NSNumber)?.doubleValue else { continue }
            let center = CLLocationCoordinate2D(latitude: lat, longitude: lng)
            // Bán kính ĐÁNH THỨC (≥ 100 m). Bán kính thật (có thể 10 m) do
            // BgGeo.evaluateCircles() chấm trên từng mẫu GPS.
            let region = CLCircularRegion(center: center,
                                          radius: BgGeoGeofences.nativeRadius(radius),
                                          identifier: id)
            region.notifyOnEntry = true
            region.notifyOnExit = true
            manager.startMonitoring(for: region)
            // Hỏi ngay trạng thái hiện tại: nếu đang Ở TRONG vùng thì iOS chỉ trả lời
            // qua didDetermineState (startMonitoring không bắn ENTER khi đã ở trong).
            // Nhờ vậy "đến vùng" vẫn có thông báo cả khi app vừa mở lại trong vùng.
            manager.requestState(for: region)
        }
        BgGeo.shared?.log("geofence: đăng ký \(list.count) vùng" +
                          " (bán kính đánh thức ≥ \(Int(BgGeoGeofences.minNativeRadius)) m)")
        needsSync = false
    }
}
