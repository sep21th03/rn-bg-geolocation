//
//  BgGeo.swift — module native iOS cho rn-bg-geolocation.
//  Hợp đồng method/event giống hệt bản Android (xem docs/IOS-PORT.md).
//

import Foundation
import UIKit
import CoreLocation
import CoreMotion
import AVFoundation
import AudioToolbox
import Security
import UserNotifications
import Network
import React

@objc(BgGeo)
public class BgGeo: RCTEventEmitter, CLLocationManagerDelegate, UNUserNotificationCenterDelegate {

    // MARK: - Hằng số

    /// iOS giới hạn 20 region/app cho CLCircularRegion.
    static let maxGeofences = 20

    /// Identifier của geofence "vị trí cuối" — vùng tạm quanh vị trí cuối, KHÔNG phải
    /// geofence của người dùng. iOS đánh giá vùng này ở tầng OS (WiFi/cell) và đánh thức
    /// app khi rời khỏi đó, kể cả sau khi app bị kill — đúng cơ chế lib gốc mô tả:
    /// "a stationary geofence around the last known position … iOS will completely
    /// reboot your application in the background".
    static let lastKnownRegionId = "rnbggeo-last-known"

    /// Instance đang chạy — AppDelegate dùng cho background fetch.
    public static weak var shared: BgGeo?

    /// AppDelegate đặt = true khi `launchOptions[UIApplication.LaunchOptionsKey.location]`
    /// có mặt — tức iOS vừa KHỞI ĐỘNG LẠI app vì một sự kiện vị trí (region/significant),
    /// không phải người dùng mở. Đáng tin hơn `applicationState` (lúc module init có thể
    /// là .inactive nên dễ chẩn đoán sai — đã gặp 21/10: relaunch tự động mà log ghi
    /// "người dùng mở app").
    public static var launchedForLocationEvent = false

    /**
     Gọi từ `AppDelegate.application(_:performFetchWithCompletionHandler:)`.

     iOS gọi background fetch khoảng 15 phút/lần khi app đã bị treo/bị kill (và cả sau
     reboot) — đây là nguồn đánh thức PHỤ mà lib gốc dùng (docs startOnBoot: "A system
     Background Fetch event fires (typically every ~15 minutes)").

     Ta mở GPS ~20 giây để lấy ít nhất một mẫu rồi trả về trạng thái nghỉ.
     */
    @objc public static func handleBackgroundFetch(
        _ completion: @escaping (UIBackgroundFetchResult) -> Void) {
        guard let m = shared, m.enabled else {
            completion(.noData)
            return
        }
        DispatchQueue.main.async {
            m.log("background fetch: iOS đánh thức app (gpsRunning=\(m.gpsRunning ? 1 : 0))")
            m.diag("service", "background fetch: iOS đánh thức app")
            let wasRunning = m.gpsRunning
            if !wasRunning {
                m.cancelStationaryTimer()
                m.isMoving = false
                m.stillSince = nil
                m.startUpdating()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) {
                if !m.isMoving && m.gpsRunning {
                    m.goStationary(reason: "backgroundFetch")
                }
                completion(.newData)
            }
        }
    }

    /// Số bản ghi trả cho getLocations().
    static let historyLimit = 2_000

    private static let cfgKey = "rnbggeo_config"
    private static let odoKey = "rnbggeo_odometer"
    /// Trạng thái bật/tắt được LƯU LẠI, để tiến trình mới (sau khi bị vuốt đi / OS thu hồi)
    /// biết là người dùng đã bật theo dõi. Android lưu ở SharedPreferences (Prefs.enabled).
    private static let enabledKey = "rnbggeo_enabled"
    /// Trạng thái trong/ngoài của từng vùng TRÒN, lưu qua các lần chạy.
    ///
    /// Vì sao phải lưu: iOS trả `didDetermineState(.inside)` mỗi lần app khởi động lại /
    /// mỗi lần đăng ký lại vùng (do tạo vùng mới). Nếu chỉ giữ trong RAM thì mỗi lần mở
    /// app đều bị coi là "vừa vào vùng" → bắn ENTER + thông báo "Đã đến …" dù người dùng
    /// không đi đâu cả (đại vương báo 22/10).
    private static let regionStateKey = "rnbggeo_fence_inside"
    /// Mã thiết bị ổn định — lưu ở Keychain (xem stableDeviceId()).
    private static let deviceIdKey = "rnbggeo_device_id"
    private static let keychainService = "rnbggeo"

    // MARK: - Mã thiết bị

    private static var cachedDeviceId: String?

    /**
     Mã thiết bị ỔN ĐỊNH dùng cho `device_id` gửi lên server.

     Vì sao không dùng thẳng `identifierForVendor`: IDFV đổi mỗi khi app bị xoá rồi
     cài lại (và trên Simulator là mỗi lần cài), nên MỘT chiếc iPhone hiện thành
     nhiều "thiết bị" trên bản đồ — đúng lỗi "iPhone bị tách làm 2 thiết bị".
     UUID được lưu trong Keychain: Keychain không bị xoá cùng app, nên mã giữ
     nguyên qua các lần cài lại. Lần đầu chạy thì lấy IDFV làm hạt giống; nếu
     Keychain không dùng được (hiếm) thì rơi về UserDefaults rồi UUID mới.
     */
    static func stableDeviceId() -> String {
        if let cached = cachedDeviceId { return cached }
        if let saved = keychainRead(deviceIdKey) {
            cachedDeviceId = saved
            return saved
        }
        if let saved = UserDefaults.standard.string(forKey: deviceIdKey), !saved.isEmpty {
            keychainWrite(deviceIdKey, saved)
            cachedDeviceId = saved
            return saved
        }
        let id = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        keychainWrite(deviceIdKey, id)
        UserDefaults.standard.set(id, forKey: deviceIdKey)
        cachedDeviceId = id
        return id
    }

    /**
     Tên hiển thị trên bản đồ. Trên Simulator, `UIDevice.model` cũng trả "iPhone"
     nên phải thêm hậu tố để không lẫn với máy thật trong bộ lọc thiết bị.
     */
    static var deviceDisplayName: String {
        #if targetEnvironment(simulator)
        return UIDevice.current.model + " (Simulator)"
        #else
        return UIDevice.current.model
        #endif
    }

    private static func keychainRead(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data,
              let text = String(data: data, encoding: .utf8),
              !text.isEmpty else { return nil }
        return text
    }

    @discardableResult
    private static func keychainWrite(_ account: String, _ value: String) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    // MARK: - Nhật ký file + sự kiện chẩn đoán

    private let logQueue = DispatchQueue(label: "rnbggeo.log")

    /**
     Ghi một dòng vào `Library/Logs/rnbggeo.log` khi `logToFile = true`.

     Trước đây iOS chỉ có getLog/getLogSize/getLogPath nhưng KHÔNG có chỗ nào ghi,
     nên khi mất đoạn hành trình (21/10: 11:44 → 13:25) không có manh mối nào trên
     máy. Android đã có file log từ lâu; iOS nay ghi giống vậy.
     */
    func log(_ message: String) {
        guard (config["logToFile"] as? Bool) ?? false else { return }
        let maxBytes = (config["logMaxBytes"] as? NSNumber)?.intValue ?? (16 * 1024 * 1024)
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        let line = f.string(from: Date()) + " " + message + "\n"
        guard let data = line.data(using: .utf8) else { return }
        logQueue.async { [weak self] in
            guard let self = self else { return }
            let url = self.logURL
            if let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber,
               size.intValue + data.count > maxBytes {
                let old = url.deletingLastPathComponent().appendingPathComponent("rnbggeo.1.log")
                try? FileManager.default.removeItem(at: old)
                try? FileManager.default.moveItem(at: url, to: old)
            }
            if let fh = try? FileHandle(forWritingTo: url) {
                fh.seekToEndOfFile()
                fh.write(data)
                try? fh.close()
            } else {
                try? data.write(to: url)
            }
        }
    }

    /**
     Đẩy một sự kiện chẩn đoán lên server khi `postDiagEvents = true`.
     Cùng quy ước với Android: `<url>/diag` (url mặc định là .../locations).
     */
    private func diag(_ kind: String, _ message: String) {
        log("diag \(kind): \(message)")
        guard (config["postDiagEvents"] as? Bool) ?? false else { return }
        var base = ((config["url"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
        while base.hasSuffix("/") { base.removeLast() }
        guard !base.isEmpty, let url = URL(string: base + "/diag") else { return }
        let headers = (config["headers"] as? [String: String]) ?? [:]
        uploader.postEvent(url: url, headers: headers, json: [
            "diag": [
                "kind": kind,
                "message": message,
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
                // Đồng nhất với Android: server/viewer đọc `device_platform` và `device_name`.
                // Trước đây iOS chỉ gửi `platform` và thiếu `device_name` nên dòng diag của
                // iPhone hiện trên viewer là "không rõ máy" (Android thì có).
                "platform": "ios",
                "device_platform": "ios",
                "device_name": BgGeo.deviceDisplayName,
                "device_id": BgGeo.stableDeviceId(),
            ],
        ])
    }


    // MARK: - Trạng thái

    let defaults = UserDefaults.standard
    private(set) var config: [String: Any] = [:]
    private let lm = CLLocationManager()
    private let motion = CMMotionActivityManager()
    private let db = BgGeoDb.shared
    private let uploader = BgGeoUploader.shared

    private var hasListeners = false
    private(set) var enabled = false
    /// true = tiến trình này do iOS đánh thức ở nền (SLC/geofence), không phải người dùng mở app.
    private(set) var didLaunchInBackground = false
    private(set) var isMoving = false
    private(set) var activity = "unknown"
    private(set) var odometer: Double = 0

    private var lastLocation: CLLocation?
    private var anchor: CLLocation?      // mốc để phát hiện rời chỗ
    private var stillSince: Date?
    private var heartbeatTimer: Timer?
    /// Hẹn giờ tắt GPS sau stopTimeout phút kể từ lúc phát hiện đứng yên.
    private var stationaryTimer: Timer?
    private var lastEmitted: Date = .distantPast
    private var watchCount = 0
    /// GPS thường có đang chạy không (goStationary() tắt nó để tiết kiệm pin).
    private var gpsRunning = false
    private var backgroundTasks: [UIBackgroundTaskIdentifier] = []
    /**
     iOS 17+: `CLBackgroundActivitySession` — giữ app chạy nền liên tục.

     VÌ SAO CẦN (đo 22/10, iPhone 11 Pro / iOS 26.6.2): Apple ghi rõ trong tài liệu
     `allowsBackgroundLocationUpdates` — hệ thống chỉ cấu hình "giữ app chạy để nhận
     location liên tục" khi `startUpdatingLocation()` được gọi lúc app ĐANG Ở FOREGROUND.
     Khi iOS tự đánh thức app ở nền (geofence/SLC) rồi ta mới gọi startUpdatingLocation,
     hệ thống KHÔNG giữ app → app bị treo sau ~10 giây, các mẫu location bị xếp hàng và
     chỉ được giao theo lô. Log 22/10 cho thấy đúng vậy: mỗi lần đánh thức ghi được
     6–10 mẫu trong ~10 giây rồi im 5 phút (mất 1.501 m giữa 2 mẫu).
     */
    private var bgActivitySession: Any?
    private var regionState: [String: Bool] = [:]
    /// Đang ở chế độ "ngủ đông": GPS vẫn chạy nhưng accuracy thô + distanceFilter 100 m
    /// (xem goStationary). Phải khôi phục Best/10 m ngay khi phát hiện di chuyển.
    private var lowPowerGps = false
    /// Lần cuối cập nhật geofence "vị trí cuối" (giây). Khi đang đi, cập nhật mỗi
    /// `safetyGeofenceIntervalMinutes` phút để lúc nào cũng có vùng quanh vị trí hiện tại.
    /// Lần cuối cập nhật geofence "vị trí cuối" (giây). Khi đang đi, cập nhật mỗi
    /// `safetyGeofenceIntervalMinutes` phút để lúc nào cũng có vùng quanh vị trí hiện tại.
    private var lastRegionUpdate: Date = .distantPast
    /// Lần cuối gửi diag "alive" (Android gửi 15 phút/lần — xem TrackingService.heartbeat).
    private var lastAliveAt: Date = .distantPast
    /// Lần cuối NHẬN ĐƯỢC mẫu vị trí (cho watchdog "provider im lặng").
    private var lastProviderSampleAt: Date = .distantPast
    private var lastProviderKickAt: Date = .distantPast
    private var providerKicks = 0
    /// Đếm số mẫu đã ghi để áp chính sách lưu trữ theo lô (20 mẫu/lần).
    private var insertCount = 0
    /// Giám sát mạng thật (trước đây getConnectivityInfo trả cứng connected=true).
    private let pathMonitor = NWPathMonitor()
    private var netConnected = true
    private var netType = "unknown"
    /// Watchdog khi đang tạm dừng trong vùng (giống Android 10 phút/lần).
    private var insideWatchdogTimer: Timer?
    /// true khi mẫu GPS đến từ watchdog (không được tự bật GPS liên tục).
    private var inWatchdog = false

    // MARK: - Vòng đời module

    public override init() {
        super.init()
        // Để AppDelegate (background fetch) gọi được vào instance đang chạy.
        BgGeo.shared = self
        // Lúc module được tạo, app đang ở .background nghĩa là iOS vừa đánh thức tiến trình
        // bằng significant-location-change hoặc geofence (không phải người dùng chạm icon).
        // Kèm cờ launchOptions (.location) do AppDelegate đặt — chính xác hơn applicationState.
        didLaunchInBackground = BgGeo.launchedForLocationEvent
            || UIApplication.shared.applicationState == .background
        config = BgGeo.defaultConfig()
        if let saved = defaults.dictionary(forKey: BgGeo.cfgKey) {
            saved.forEach { config[$0.key] = $0.value }
        }
        odometer = defaults.double(forKey: BgGeo.odoKey)
        loadRegionState()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyBest
        lm.distanceFilter = 10
        lm.activityType = .otherNavigation
        lm.pausesLocationUpdatesAutomatically = false
        if #available(iOS 9.0, *) { lm.allowsBackgroundLocationUpdates = false }
        // Như trên: tiến trình bị OS đánh thức ở nền thì phải bật cờ này ngay từ init,
        // trước khi startUpdatingLocation() — nếu không iOS treo app sau ~10 giây.
        if ((config["stopOnTerminate"] as? Bool) ?? true) == false,
           defaults.bool(forKey: BgGeo.enabledKey) {
            lm.allowsBackgroundLocationUpdates = true
        }
        uploader.onResult = { [weak self] payload in
            self?.emit("rnbggeo:http", payload)
            // Ghi kết quả HTTP vào log file: khi app ở nền JS không nhận được event, mà đây
            // chính là chỗ duy nhất cho biết VÌ SAO điểm không lên được server (28/10: cần debug).
            let ok = (payload["success"] as? Bool) ?? false
            let st = (payload["status"] as? Int) ?? 0
            let resp = String(((payload["response"] as? String) ?? "").prefix(160))
            self?.log("http result: success=\(ok) status=\(st) resp=\(resp)")
        }
        uploader.onBody = { [weak self] body in
            let prefix = String(body.prefix(160))
            self?.log("http body: \(prefix)")
        }
        // Chẩn đoán vòng đời: chỉ nhìn log là biết app bị đưa vào nền/ra nền lúc nào và
        // lúc đó còn quyền chạy nền hay không (đây là mấu chốt của việc mất hành trình).
        NotificationCenter.default.addObserver(
            self, selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(appWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(appDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification, object: nil)
        // Chỉ bắn khi tiến trình THẬT SỰ bị kết thúc — không bắn khi người dùng vuốt app
        // khỏi đa nhiệm (SIGKILL). Vì vậy chỗ chính để "chốt vùng trước khi chết" là
        // appDidEnterBackground(); xem appWillTerminate().
        NotificationCenter.default.addObserver(
            self, selector: #selector(appWillTerminate),
            name: UIApplication.willTerminateNotification, object: nil)
        setupNotificationCenter()
        startConnectivityMonitor()
        // iOS có thể KHỞI ĐỘNG LẠI app ở nền (significant-location / geofence) mà KHÔNG
        // chạy ready() của JS. Khi đó `enabled` trong RAM = false → startUpdating() bị
        // chặn, app chỉ ghi được đúng mẫu đánh thức rồi ngủ tiếp. Đây chính là lỗ hổng
        // làm mất đoạn 11:44 → 13:25 ngày 21/10 (2 máy đi cùng nhau, Android ghi đủ).
        restoreTrackingOnLaunch()
    }

    public override static func requiresMainQueueSetup() -> Bool { true }

    public override func supportedEvents() -> [String]! {
        return [
            "rnbggeo:location", "rnbggeo:motionchange", "rnbggeo:geofence",
            "rnbggeo:geofenceschange", "rnbggeo:heartbeat", "rnbggeo:providerchange",
            "rnbggeo:authorization", "rnbggeo:activitychange", "rnbggeo:enabledchange",
            "rnbggeo:connectivity", "rnbggeo:powersavechange", "rnbggeo:http",
            "rnbggeo:schedule", "rnbggeo:locationfilter",
        ]
    }

    public override func startObserving() { hasListeners = true }
    public override func stopObserving() { hasListeners = false }

    private func emit(_ name: String, _ body: Any) {
        guard hasListeners else { return }
        // Lớp JS (index.js) đọc `e.payload` và tự JSON.parse — Android cũng bọc như vậy.
        // Bắn dict trần sẽ làm `e.payload` = undefined → app crash.
        let text: String
        if let s = body as? String {
            text = s
        } else if JSONSerialization.isValidJSONObject(body),
                  let d = try? JSONSerialization.data(withJSONObject: body),
                  let s = String(data: d, encoding: .utf8) {
            text = s
        } else {
            text = "{}"
        }
        let payload: [String: Any] = ["payload": text]
        if Thread.isMainThread {
            sendEvent(withName: name, body: payload)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.sendEvent(withName: name, body: payload)
            }
        }
    }

    // MARK: - Vòng đời app & thông báo

    @objc private func appDidEnterBackground() {
        diag("service", "app VÀO NỀN — gpsRunning=\(gpsRunning ? 1 : 0)" +
            " allowsBg=\(lm.allowsBackgroundLocationUpdates ? 1 : 0)" +
            " session=\(bgActivitySession == nil ? 0 : 1)")
        // ĐÂY MỚI LÀ ĐIỂM "TRƯỚC KHI BỊ KILL" THỰC TẾ TRÊN iOS.
        // iOS gọi didEnterBackground ở MỌI lần app rời foreground rồi vài giây sau treo
        // tiến trình; còn applicationWillTerminate thì KHÔNG được gọi khi người dùng vuốt
        // app khỏi đa nhiệm hay khi OS thu hồi một app đang treo. Vì vậy phải chốt vùng
        // quanh vị trí cuối ngay ở đây — nếu không, app bị kill lúc đang đi sẽ chỉ còn vùng
        // cũ (tối đa 5 phút tuổi, đi 40 km/h là lệch ~3,3 km) và OS đánh thức sai chỗ.
        registerRegionBeforeTermination(reason: "vào nền")
    }

    @objc private func appWillEnterForeground() {
        diag("service", "app RA NỀN — gpsRunning=\(gpsRunning ? 1 : 0)" +
            " allowsBg=\(lm.allowsBackgroundLocationUpdates ? 1 : 0)")
        // CƠ CHẾ "CHẠY NỀN LIÊN TỤC" CỦA APPLE (không cần viên xanh):
        // Apple chỉ cấu hình hệ thống giữ app chạy nền nếu startUpdatingLocation() được gọi
        // khi app ĐANG Ở FOREGROUND. Vì vậy mỗi lần người dùng mở app, ta bật lại cờ + gọi
        // startUpdatingLocation() (chỉ khi GPS đang chạy, không đánh thức GPS lúc đứng yên).
        // Nhờ vậy KHÔNG cần CLBackgroundActivitySession ⇒ status bar chỉ có mũi tên vị trí.
        if enabled {
            if #available(iOS 9.0, *) { lm.allowsBackgroundLocationUpdates = true }
            if gpsRunning { lm.startUpdatingLocation() }
        }
    }

    /// Cold start bằng cách chạm icon: `willEnterForeground` KHÔNG bắn, chỉ có
    /// `didBecomeActive` → phải bắt ở đây mới cấu hình được "chạy nền liên tục".
    @objc private func appDidBecomeActive() {
        if enabled {
            if #available(iOS 9.0, *) { lm.allowsBackgroundLocationUpdates = true }
            if gpsRunning { lm.startUpdatingLocation() }
        }
    }

    /**
     Chốt geofence "vị trí cuối" NGAY TRƯỚC KHI TIẾN TRÌNH BỊ KẾT THÚC.

     Đây đúng là cơ chế lib gốc transistorsoft dùng cho `stopOnTerminate: false`:

       "Before termination, the SDK registers a geofence around the last known position.
        When the device exits that geofence, iOS fully relaunches the app in the background
        and tracking resumes automatically. This mechanism survives device reboot because
        iOS monitors geofences at the OS level."          — docs AppConfig.stopOnTerminate

     Ta đã dời vùng này mỗi `safetyGeofenceIntervalMinutes` phút lúc đang đi và khi vào
     trạng thái đứng yên, nhưng vẫn hở đúng một khoảng: app bị kill (vuốt khỏi đa nhiệm,
     OS thu hồi, hết pin) NGAY SAU khi rời foreground — lúc đó vùng đang có có thể cũ tới
     5 phút nên OS đánh thức sai chỗ, và đoạn đầu chuyến đi bị mất (đúng ca 23/10: mất
     399 m đầu chuyến vì phải chờ sự kiện vùng).

     Vì sao bọc `beginBackgroundTask`: sau khi vào nền iOS chỉ cho vài giây CPU trước khi
     treo tiến trình. Region sống ở tầng OS nên sau khi đăng ký xong thì app có treo cũng
     không sao — nhưng phải đảm bảo lệnh `startMonitoring` + ghi log chạy xong.
     */
    private func registerRegionBeforeTermination(reason: String) {
        // Chỉ có nghĩa khi tracking còn sống sau khi app chết (stopOnTerminate=false).
        // stopOnTerminate=true ⇒ lib gốc "calls stop() as the app terminates", tức không
        // đăng ký gì để OS khỏi đánh thức app lên một cách vô ích.
        guard (config["stopOnTerminate"] as? Bool) == false else { return }
        guard enabled, isAuthorized else { return }
        guard lastLocation != nil || lm.location != nil else {
            log("geofence trước khi kết thúc (\(reason)): chưa có vị trí nào để đăng ký")
            return
        }
        var task: UIBackgroundTaskIdentifier = .invalid
        task = UIApplication.shared.beginBackgroundTask(withName: "rnbggeo-region-before-terminate") {
            // Hết hạn: iOS sắp treo app → trả task ngay.
            if task != .invalid {
                UIApplication.shared.endBackgroundTask(task)
                task = .invalid
            }
        }
        updateLastKnownRegion()
        let radius = Int(max((config["stationaryRadius"] as? NSNumber)?.doubleValue ?? 200, 100))
        diag("service", "geofence trước khi kết thúc (\(reason)) — " +
            "vùng \(radius) m quanh vị trí cuối, OS sẽ relaunch app khi rời khỏi vùng")
        // `startMonitoring` là lời gọi đồng bộ (không chờ mạng) → trả task sau 1 giây.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            if task != .invalid {
                UIApplication.shared.endBackgroundTask(task)
                task = .invalid
            }
        }
    }

    /**
     iOS CHỈ gọi `applicationWillTerminate` khi app ĐANG CHẠY mà bị kết thúc (hết pin,
     OS thu hồi tiến trình, khởi động lại máy). Vuốt app khỏi đa nhiệm là SIGKILL nên
     không có callback nào — vì vậy `appDidEnterBackground()` mới là chỗ chính.

     Ở đây làm đúng ngữ nghĩa `stopOnTerminate` của lib gốc:
       - false → chốt lại vùng quanh vị trí cuối để OS relaunch app khi rời chỗ;
       - true  → "the SDK calls stop() as the app terminates" ⇒ xoá vùng của ta để OS
                 không đánh thức app lên một cách vô ích.
     */
    @objc private func appWillTerminate() {
        if (config["stopOnTerminate"] as? Bool) ?? true {
            clearLastKnownRegion()
            diag("service", "app SẮP KẾT THÚC — stopOnTerminate=true → xoá geofence vị trí cuối")
        } else {
            registerRegionBeforeTermination(reason: "app sắp kết thúc")
        }
    }

    /**
     Đăng ký thông báo hệ thống cho geofence (bản iOS trước đây KHÔNG có gì).

     Android đã có GeofenceNotifier (thông báo khi vào/ra vùng); iOS thiếu hoàn toàn nên
     "lúc đến geofence không thấy thông báo". Quyền thông báo phải được người dùng đồng ý
     → hỏi một lần khi module khởi tạo (chỉ khi trạng thái còn là .notDetermined).
     */
    private func setupNotificationCenter() {
        guard (config["geofenceNotifications"] as? Bool) ?? true else { return }
        let center = UNUserNotificationCenter.current()
        // Để thông báo vẫn hiện khi app đang mở (mặc định iOS giấu).
        if center.delegate == nil { center.delegate = self }
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .notDetermined else { return }
            center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, _ in
                self?.log("thông báo: quyền = \(granted ? 1 : 0)")
            }
        }
    }

    /// Hiện thông báo khi app đang foreground (iOS giấu mặc định).
    public func userNotificationCenter(_ center: UNUserNotificationCenter,
                                       willPresent notification: UNNotification,
                                       withCompletionHandler completionHandler:
                                       @escaping (UNNotificationPresentationOptions) -> Void) {
        if #available(iOS 14.0, *) {
            completionHandler([.banner, .sound])
        } else {
            completionHandler([.alert, .sound])
        }
    }

    /**
     Thông báo "Đã đến / Đã rời <tên vùng>" — tương đương GeofenceNotifier bên Android.
     Tôn trọng `geofenceNotifications` (chung) và notifyOnEntry/notifyOnExit (từng vùng).
     */
    private func notifyGeofence(_ payload: [String: Any], enter: Bool, initial: Bool = false) {
        guard (config["geofenceNotifications"] as? Bool) ?? true else { return }
        // ENTER kiểu "đang ở trong vùng lúc mở app / lúc vừa tạo vùng" KHÔNG phải "vừa đến"
        // → không báo. Trước 22/10 mỗi lần mở app lại nhận 1 thông báo "Đã đến <tên>"
        // (do didDetermineState trả .inside) dù không hề đi đâu.
        guard !initial else {
            log("thông báo geofence: bỏ qua ENTER ban đầu (\(payload["name"] as? String ?? ""))")
            return
        }
        // Chế độ: 'always' = báo cả khi app ở nền; 'foreground' = chỉ báo khi app đang mở.
        let mode = (config["geofenceNotificationMode"] as? String) ?? "always"
        if mode == "foreground", UIApplication.shared.applicationState != .active {
            log("thông báo geofence: app ở nền, chế độ foreground → không báo")
            return
        }
        let perFence = (payload[enter ? "notifyOnEntry" : "notifyOnExit"] as? Bool) ?? true
        guard perFence else { return }
        let name = (payload["name"] as? String)
            ?? (payload["identifier"] as? String) ?? "vùng"
        let content = UNMutableNotificationContent()
        content.title = (config["notificationTitle"] as? String) ?? "bgGeoLab"
        // App TỰ ĐẶT nội dung qua `extras` của vùng (lib không biết nghiệp vụ):
        //   extras.entryTitle / exitTitle → tiêu đề;  extras.note → dòng thêm.
        let ex = (payload["extras"] as? [String: Any]) ?? [:]
        let custom = ((enter ? ex["entryTitle"] : ex["exitTitle"]) as? String) ?? ""
        content.body = custom.isEmpty ? ((enter ? "Đã đến " : "Đã rời ") + name) : custom
        if let loc = payload["location"] as? [String: Any],
           let c = loc["coords"] as? [String: Any],
           let lat = c["latitude"] as? NSNumber, let lng = c["longitude"] as? NSNumber {
            content.body += String(format: " · %.5f, %.5f", lat.doubleValue, lng.doubleValue)
        }
        content.sound = .default
        let req = UNNotificationRequest(identifier: "rnbggeo-fence-" + UUID().uuidString,
                                        content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req) { [weak self] err in
            if let err = err {
                self?.diag("geofence", "gửi thông báo lỗi: \(err.localizedDescription)")
            } else {
                // Log cả khi THÀNH CÔNG: đọc log là biết thông báo đã được đặt vào Notification
                // Center (trước đây chỉ log lỗi nên không chứng minh được gì).
                self?.log("thông báo: đã đặt \"\(content.body)\"")
            }
        }
    }

    // MARK: - Giữ app chạy nền (iOS 17+)

    private func ensureBackgroundSession() {
        // Mặc định TẮT (0.1.8): chỉ bật khi config nói rõ true (cần ghi hành trình liền mạch).
        guard (config["backgroundActivitySession"] as? Bool) ?? false else { return }
        if #available(iOS 17.0, *) {
            guard bgActivitySession == nil else { return }
            bgActivitySession = CLBackgroundActivitySession()
            diag("service", "background activity session: BẬT (giữ app chạy nền liên tục)")
        }
    }

    private func endBackgroundSession() {
        if #available(iOS 17.0, *), let s = bgActivitySession as? CLBackgroundActivitySession {
            s.invalidate()
            diag("service", "background activity session: TẮT (đứng yên / dừng tracking)")
        }
        bgActivitySession = nil
    }

    // MARK: - Cấu hình

    static func defaultConfig() -> [String: Any] {
        return [
            "desiredAccuracy": "high",
            "distanceFilter": 10,
            "stopTimeout": 5,
            "stationaryRadius": 25,
            "heartbeatInterval": 60,
            "autoSync": true,
            "batchSync": false,
            "maxBatchSize": 500,
            "url": "",
            // Giống lib gốc: mặc định DỪNG khi app bị kill. Đặt false thì tiến trình mới
            // (sau khi vuốt app đi) tự bật lại theo dõi ở ready().
            "stopOnTerminate": true,
            "startOnBoot": false,
            "headers": [String: String](),
            "pauseInsideGeofence": false,
            // iOS: thông báo hệ thống khi vào/ra vùng.
            "geofenceNotifications": true,
            // iOS: "always" = báo cả khi app ở nền/đang đóng; "foreground" = chỉ báo khi app
            // đang mở. ENTER kiểu "đang ở trong vùng lúc mở app / lúc tạo vùng" LUÔN không báo.
            "geofenceNotificationMode": "always",
            "geofenceInitialTriggerEntry": true,
            "zeroLossMode": false,
            // iOS: giữ GPS thô 100 m khi đứng yên thay vì tắt hẳn. Mặc định FALSE để
            // KHÔNG hiện mũi tên vị trí trên status bar khi máy đang đứng yên (người
            // dùng không thích); đánh thức khi đi tiếp do "geofence đứng yên" đảm nhận.
            "stationaryLowPowerGps": false,
            // iOS: khi ĐANG ĐI, dời geofence "vị trí cuối" mỗi N phút (mô hình lib gốc)
            // để nếu app bị kill giữa đường thì OS vẫn có vùng để đánh thức. 0 = tắt.
            "safetyGeofenceIntervalMinutes": 5,
            // iOS: giữ app sống khi đứng yên (giống preventSuspend của lib gốc).
            // Tốn pin + mũi tên vị trí hiện liên tục → mặc định TẮT.
            "preventSuspend": false,
            // iOS: nhịp Background Fetch (phút). 0 = tắt. iOS thực tế ~15 phút/lần.
            "backgroundFetchIntervalMinutes": 15,
            // iOS 17+: CLBackgroundActivitySession — GIỮ APP CHẠY NỀN LIÊN TỤC.
            //
            // MẶC ĐỊNH TẮT (0.1.8) — theo đúng triết lý "pin trước" của lib gốc và phù hợp
            // với app CHẤM CÔNG: người dùng không chấp nhận "viền xanh" hiện thường trực, và
            // đứng yên thì KHÔNG được giữ location chạy.
            //
            // BẬT (true) khi cần VẼ HÀNH TRÌNH LIỀN MẠCH: đo thật 26/10 — mở app (foreground)
            // rồi khoá màn hình, session BẬT ⇒ ghi 682 điểm / 9,18 km / 22 phút, odometer
            // khớp đường vẽ 99,8 %. Đổi lại: status bar LUÔN hiện "viền xanh".
            //
            // Vì sao KHÔNG cần cho chấm công: ENTER/EXIT vùng do ta tự chấm trên từng mẫu GPS
            // (point-in-circle/polygon), native region chỉ là "chuông đánh thức". Khi người
            // dùng DI CHUYỂN thì app đã thức (do vùng "vị trí cuối" đánh thức) nên mốc vào
            // vùng chính xác tới ~1–2 giây. Mốc RỜI vùng trễ ~200 m (giới hạn của iOS, xem
            // mục 11 báo cáo) — đánh đổi để không tốn pin khi đứng yên.
            "backgroundActivitySession": false,
            // Mũi tên vị trí trên status bar khi chạy nền (lib gốc mặc định false).
            "showsBackgroundLocationIndicator": false,
            // Nhịp gửi diag "alive" (phút) — giống Android 15 phút/lần; 0 = tắt.
            "aliveIntervalMinutes": 15,
            // Chính sách lưu trữ (giống Android): -1 = giữ mãi.
            "maxDaysToPersist": -1,
            "maxRecordsToPersist": -1,
            // Watchdog khi tạm dừng trong vùng (phút) — lấy 1 mẫu thô để biết còn trong
            // vùng hay đã đi; 0 = tắt. Giống Android (10 phút).
            "insideWatchdogMinutes": 10,
            "geofenceProximityRadius": 1000,
            "logLevel": 3,
            "schedule": [String](),
        ]
    }

    private func saveConfig() {
        defaults.set(config, forKey: BgGeo.cfgKey)
    }

    /**
     Đặt nhịp Background Fetch (mặc định 15 phút, 0 = tắt).

     iOS dùng nó để đánh thức app đã bị treo/bị kill khoảng 15 phút/lần — nguồn đánh
     thức phụ mà lib gốc dùng. App cần `UIBackgroundModes: fetch` và AppDelegate phải
     chuyển `application(_:performFetchWithCompletionHandler:)` vào
     `BgGeo.handleBackgroundFetch(_:)`.
     */
    private func applyBackgroundFetchInterval() {
        let minutes = (config["backgroundFetchIntervalMinutes"] as? NSNumber)?.doubleValue ?? 15
        let interval: TimeInterval = minutes > 0
            ? minutes * 60
            : UIApplication.backgroundFetchIntervalNever
        UIApplication.shared.setMinimumBackgroundFetchInterval(interval)
        log("background fetch: đặt nhịp " + (minutes > 0 ? "\(Int(minutes)) phút" : "TẮT"))
    }

    private func applyLocationSettings() {
        switch (config["desiredAccuracy"] as? String) ?? "high" {
        case "low", "passive": lm.desiredAccuracy = kCLLocationAccuracyHundredMeters
        case "balanced": lm.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        default: lm.desiredAccuracy = kCLLocationAccuracyBest
        }
        // activityType: trước đây hard-code .otherNavigation (config bị bỏ qua).
        switch (config["activityType"] as? String) ?? "otherNavigation" {
        case "automotiveNavigation": lm.activityType = .automotiveNavigation
        case "fitness": lm.activityType = .fitness
        case "airborne": lm.activityType = .airborne
        case "other": lm.activityType = .other
        default: lm.activityType = .otherNavigation
        }
        lm.distanceFilter = (config["distanceFilter"] as? NSNumber)?.doubleValue ?? 10
        // Config có thể vừa đổi true → false (ready/setConfig): phải HUỶ session đang có,
        // nếu không "viền xanh" vẫn nằm trên status bar tới khi tiến trình chết.
        // Mặc định TẮT (0.1.8) ⇒ huỷ trừ khi config nói rõ true.
        if !((config["backgroundActivitySession"] as? Bool) ?? false) {
            endBackgroundSession()
        }
    }

    private func state() -> [String: Any] {
        var s: [String: Any] = [
            "enabled": enabled,
            "isMoving": isMoving,
            "odometer": odometer,
            "activity": activity,
            "trackingMode": enabled ? (isMoving ? 1 : 0) : -1,
            "url": config["url"] ?? "",
            "geofenceCount": BgGeoGeofences.shared.all().count,
            "pendingCount": db.pendingCount(),
            "stationary": enabled && !gpsRunning,
            "stopOnTerminate": (config["stopOnTerminate"] as? Bool) ?? true,
            "didLaunchInBackground": didLaunchInBackground,
            // Giá trị CONFIG (người dùng đặt) + trạng thái THẬT của session, để app hiện
            // được công tắc đúng và chip "session đang sống hay không" mà không phải mò log.
            "backgroundActivitySession": (config["backgroundActivitySession"] as? Bool) ?? true,
            "bgActivitySession": bgActivitySession != nil,
        ]
        if let loc = lastLocation {
            s["lastLocation"] = BgGeo.locationDict(
                loc, uuid: UUID().uuidString, odometer: odometer,
                activity: activity, isMoving: isMoving
            )
        }
        return s
    }

    /// Dựng chuỗi JSON để trả về JS. Là `static` để gọi được từ closure không giữ `self`
    /// (xem `uploadLog` — ở đó `self` có thể đã bị giải phóng khi URLSession trả lời).
    static func encodeJson(_ obj: Any) -> String {
        guard JSONSerialization.isValidJSONObject(obj),
              let d = try? JSONSerialization.data(withJSONObject: obj) else { return "{}" }
        return String(data: d, encoding: .utf8) ?? "{}"
    }

    private func jsonString(_ obj: Any) -> String {
        return BgGeo.encodeJson(obj)
    }

    private func jsonArray(_ arr: [Any]) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: arr) else { return "[]" }
        return String(data: d, encoding: .utf8) ?? "[]"
    }

    // MARK: - API vòng đời

    @objc func ready(_ cfg: NSDictionary,
                     resolver resolve: @escaping RCTPromiseResolveBlock,
                     rejecter reject: @escaping RCTPromiseRejectBlock) {
        if let dict = cfg as? [String: Any] {
            dict.forEach { config[$0.key] = $0.value }
        }
        saveConfig()
        applyLocationSettings()
        applyBackgroundFetchInterval()
        BgGeoGeofences.shared.syncRegions(with: lm)
        // Khôi phục trạng thái bật/tắt đã lưu (nếu stopOnTerminate = false).
        resumeIfNeeded()
        warnUnsupportedConfig()
        resolve(jsonString(state()))
    }

    @objc func start(_ resolve: @escaping RCTPromiseResolveBlock,
                     rejecter reject: @escaping RCTPromiseRejectBlock) {
        // CLLocationManager + Timer phải chạy trên main run loop.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.enabled = true
            self.defaults.set(true, forKey: BgGeo.enabledKey)
            self.cancelStationaryTimer()
            // Không mặc định "đang di chuyển": máy để trên bàn cũng phải hiện đứng yên.
            self.isMoving = false
            self.anchor = self.lastLocation
            self.stillSince = nil
            self.startUpdating()
            self.startMotion()
            self.startHeartbeat()
            self.requestPermissionInternal()
            self.emit("rnbggeo:enabledchange", ["enabled": true])
            self.diag("service", "start() → theo dõi BẬT")
            resolve(self.jsonString(self.state()))
        }
    }

    @objc func stop(_ resolve: @escaping RCTPromiseResolveBlock,
                    rejecter reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.enabled = false
            self.defaults.set(false, forKey: BgGeo.enabledKey)
            self.cancelStationaryTimer()
            self.cancelInsideWatchdog()
            self.isMoving = false
            self.stopUpdating()
            self.clearLastKnownRegion()
            self.motion.stopActivityUpdates()
            self.heartbeatTimer?.invalidate()
            self.heartbeatTimer = nil
            self.emit("rnbggeo:enabledchange", ["enabled": false])
            self.diag("service", "stop() → theo dõi TẮT")
            resolve(self.jsonString(self.state()))
        }
    }

    /// iOS không có START_STICKY như Android. Sau khi app bị gỡ khỏi đa nhiệm (hoặc OS thu hồi),
    /// tiến trình mới mất cờ `enabled` trong RAM → phải đọc lại từ UserDefaults.
    /// Chỉ khôi phục khi `stopOnTerminate = false` + còn quyền vị trí; mặc định (true) là dừng.
    private func resumeIfNeeded() {
        guard !enabled else { return }
        let stopOnTerminate = (config["stopOnTerminate"] as? Bool) ?? true
        guard stopOnTerminate == false else { return }
        guard defaults.bool(forKey: BgGeo.enabledKey) else { return }
        guard isAuthorized else {
            // Quyền bị thu hồi → không thể theo dõi; trả trạng thái về TẮT cho đúng sự thật.
            defaults.set(false, forKey: BgGeo.enabledKey)
            return
        }
        if Thread.isMainThread {
            applyResume()
        } else {
            DispatchQueue.main.sync { [weak self] in self?.applyResume() }
        }
    }

    private func applyResume() {
        enabled = true
        isMoving = false
        anchor = lastLocation
        stillSince = nil
        startUpdating()
        startMotion()
        startHeartbeat()
        emit("rnbggeo:enabledchange", ["enabled": true])
        diag("service", "resumeIfNeeded → theo dõi BẬT (stopOnTerminate=false)")
    }

    /**
     Khôi phục theo dõi NGAY khi module được tạo, không chờ JS gọi ready().

     Vì sao cần: iOS đánh thức app ở nền bằng significant-location/geofence, nhưng
     `enabled` chỉ là biến RAM, khôi phục duy nhất trong ready()/start(). Nếu JS chưa
     kịp chạy ready() thì startUpdating() bị `guard enabled` chặn → app nhận đúng mẫu
     đánh thức rồi im lặng. Điều kiện giống resumeIfNeeded(): stopOnTerminate=false,
     người dùng đã bật theo dõi lần trước, còn quyền vị trí.
     */
    private func restoreTrackingOnLaunch() {
        guard !enabled else { return }
        guard (config["stopOnTerminate"] as? Bool) == false else { return }
        guard defaults.bool(forKey: BgGeo.enabledKey) else { return }
        guard isAuthorized else { return }
        enabled = true
        isMoving = false
        anchor = lastLocation
        stillSince = nil
        startUpdating()
        startMotion()
        startHeartbeat()
        diag("service", "khôi phục theo dõi lúc khởi động (" +
            (didLaunchInBackground ? "iOS đánh thức ở nền" : "người dùng mở app") + ")")
    }

    /**
     Nhận được một mẫu vị trí trong khi GPS thường đang tắt (tạm nghỉ vì đứng yên,
     hoặc tiến trình vừa bị iOS đánh thức) → bật lại GPS NGAY ở mẫu đầu tiên.

     Trước đây phải đợi mẫu thứ hai cách mốc > stationaryRadius mới bật, nên một
     chuyến đi chỉ được ghi 1 điểm mỗi ~500 m của significant-location.
     */
    private func ensureTrackingResumed() {
        guard !gpsRunning else { return }
        guard (config["stopOnTerminate"] as? Bool) == false else { return }
        guard defaults.bool(forKey: BgGeo.enabledKey) else { return }
        guard isAuthorized else { return }
        if !enabled {
            enabled = true
            startMotion()
            startHeartbeat()
            emit("rnbggeo:enabledchange", ["enabled": true])
        }
        cancelStationaryTimer()
        isMoving = false
        stillSince = nil
        anchor = nil          // mốc mới: mẫu này là điểm bắt đầu chuyến
        startUpdating()
        diag("service", "GPS bật lại khi nhận mẫu vị trí (đang tạm nghỉ / vừa được đánh thức)")
    }

    @objc func getState(_ resolve: @escaping RCTPromiseResolveBlock,
                        rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(jsonString(state()))
    }

    @objc func getConfig(_ resolve: @escaping RCTPromiseResolveBlock,
                         rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(jsonString(config))
    }

    @objc func setConfig(_ partial: NSDictionary,
                         resolver resolve: @escaping RCTPromiseResolveBlock,
                         rejecter reject: @escaping RCTPromiseRejectBlock) {
        if let dict = partial as? [String: Any] {
            dict.forEach { config[$0.key] = $0.value }
        }
        saveConfig()
        applyLocationSettings()
        applyBackgroundFetchInterval()
        if enabled { startUpdating() }
        warnUnsupportedConfig()
        resolve(jsonString(state()))
    }

    @objc func reset(_ cfg: NSDictionary,
                     resolver resolve: @escaping RCTPromiseResolveBlock,
                     rejecter reject: @escaping RCTPromiseRejectBlock) {
        stopUpdating()
        enabled = false
        isMoving = false
        odometer = 0
        defaults.removeObject(forKey: BgGeo.odoKey)
        defaults.set(false, forKey: BgGeo.enabledKey)
        config = BgGeo.defaultConfig()
        if let dict = cfg as? [String: Any] {
            dict.forEach { config[$0.key] = $0.value }
        }
        saveConfig()
        applyLocationSettings()
        resolve(jsonString(state()))
    }

    @objc func changePace(_ moving: Bool,
                          resolver resolve: @escaping RCTPromiseResolveBlock,
                          rejecter reject: @escaping RCTPromiseRejectBlock) {
        isMoving = moving
        if moving {
            stillSince = nil
            anchor = lastLocation
            if enabled { startUpdating() }
        } else {
            goStationary(reason: "changePace")
        }
        let p: [String: Any] = ["isMoving": moving, "location": lastLocationDict()]
        emit("rnbggeo:motionchange", p)
        resolve(jsonString(state()))
    }

    @objc func getCurrentPosition(_ options: NSDictionary,
                                  resolver resolve: @escaping RCTPromiseResolveBlock,
                                  rejecter reject: @escaping RCTPromiseRejectBlock) {
        // Tôn trọng options như Android/lib gốc (trước đây iOS bỏ qua: cache cứng 60 s,
        // timeout cứng 20 s). Đơn vị của cả hai là MILI-GIÂY (index.js mặc định timeout 30000).
        let opts = (options as? [String: Any]) ?? [:]
        // Giống Android: `timeout` (ms) nếu có, không thì lấy `locationTimeout` (GIÂY).
        let timeoutMs: Double = {
            if let t = (opts["timeout"] as? NSNumber)?.doubleValue { return t }
            return ((config["locationTimeout"] as? NSNumber)?.doubleValue ?? 60) * 1000
        }()
        let timeoutSec = min(max(timeoutMs / 1000, 1), 120)
        let maxAgeMs = (opts["maximumAge"] as? NSNumber)?.doubleValue ?? 5_000
        if let loc = lastLocation,
           Date().timeIntervalSince(loc.timestamp) * 1000 <= maxAgeMs {
            resolve(["location": BgGeo.locationDict(
                loc, uuid: UUID().uuidString, odometer: odometer,
                activity: activity, isMoving: isMoving)])
            return
        }
        // Đang "ngủ đông" (GPS tắt) thì requestLocation() vẫn lấy được một mẫu (nó độc lập
        // với startUpdatingLocation) — KHÔNG bật continuous updates ở đây, nếu không mỗi lần
        // app hỏi vị trí sẽ để GPS chạy thêm tới stopTimeout (hao pin vô ích).
        // Android KHÔNG ghi DB ở getCurrentPosition (chỉ trả toạ độ). iOS trước đây ghi
        // → mỗi lần app hỏi vị trí (vd. tạo geofence) sinh thêm 1 điểm rác rồi đẩy lên
        // server. Bỏ cho khớp Android.
        pendingFix = { [weak self] loc in
            guard let self = self else { return }
            let dict = BgGeo.locationDict(loc, uuid: UUID().uuidString, odometer: self.odometer,
                                          activity: self.activity, isMoving: self.isMoving)
            self.pendingFix = nil
            resolve(["location": dict])
        }
        lm.requestLocation()
        DispatchQueue.main.asyncAfter(deadline: .now() + timeoutSec) { [weak self] in
            guard let self = self, self.pendingFix != nil else { return }
            self.pendingFix = nil
            if let loc = self.lastLocation {
                resolve(["location": BgGeo.locationDict(
                    loc, uuid: UUID().uuidString, odometer: self.odometer,
                    activity: self.activity, isMoving: self.isMoving)])
            } else {
                reject("E_NO_FIX", "Chưa có vị trí (chưa được cấp quyền hoặc GPS chưa có tín hiệu)", nil)
            }
        }
    }

    private func lastLocationDict() -> Any {
        guard let loc = lastLocation else { return NSNull() }
        return BgGeo.locationDict(loc, uuid: UUID().uuidString, odometer: odometer,
                                  activity: activity, isMoving: isMoving)
    }

    // MARK: - Theo dõi

    private var pendingFix: ((CLLocation) -> Void)?

    private var isAuthorized: Bool {
        let s = lm.authorizationStatus
        return s == .authorizedAlways || s == .authorizedWhenInUse
    }

    private func startUpdating() {
        guard enabled else { return }
        applyLocationSettings()
        lowPowerGps = false
        // GPS chạy lại thì watchdog trong vùng không còn cần thiết.
        cancelInsideWatchdog()
        // Đang đi lại → dời geofence "vị trí cuối" về chỗ hiện tại (mô hình lib gốc).
        updateLastKnownRegion()
        lm.allowsBackgroundLocationUpdates = true
        // Mặc định false giống lib gốc: chỉ bật mũi tên khi cấu hình yêu cầu.
        lm.showsBackgroundLocationIndicator =
            (config["showsBackgroundLocationIndicator"] as? Bool) ?? false
        lm.startUpdatingLocation()
        lm.startMonitoringSignificantLocationChanges()
        gpsRunning = true
        ensureBackgroundSession()
    }

    private func stopUpdating() {
        lm.stopUpdatingLocation()
        lm.stopMonitoringSignificantLocationChanges()
        lm.allowsBackgroundLocationUpdates = false
        gpsRunning = false
        endBackgroundSession()
    }

    private func startMotion() {
        guard CMMotionActivityManager.isActivityAvailable() else { return }
        motion.startActivityUpdates(to: OperationQueue.main) { [weak self] act in
            guard let self = self, let a = act else { return }
            let name: String
            if a.automotive { name = "in_vehicle" }
            else if a.cycling { name = "on_bicycle" }
            else if a.running { name = "running" }
            else if a.walking { name = "on_foot" }
            else if a.stationary { name = "still" }
            else { name = "unknown" }
            if name != self.activity {
                // iOS rất hay trả về activity "unknown" (không cờ nào bật, confidence vẫn
                // có thể high) xen kẽ với "still". "unknown" KHÔNG phải "đang di chuyển"
                // và cũng không được đè lên nhãn gần nhất — nếu không Dashboard sẽ nhảy
                // unknown ⇄ di chuyển và stop-detection không bao giờ chạy.
                if name == "unknown" { return }

                self.activity = name
                // CMMotionActivityConfidence: 0=low, 1=medium, 2=high (KHÔNG phải %)
                let pct: Int
                switch a.confidence {
                case .high: pct = 100
                case .medium: pct = 60
                default: pct = 30
                }

                // Cảm biến chuyển động là nguồn đáng tin hơn "start() = đang đi".
                // Máy đặt trên bàn: activity=still → phải hiện ĐANG ĐỨNG YÊN.
                // Tin cả confidence thấp, miễn là GPS cũng báo đứng yên (speed < 0.5).
                if name == "still" {
                    let slow = (self.lastLocation?.speed ?? 0) < 0.5
                    if self.isMoving && slow {
                        self.isMoving = false
                        self.stillSince = Date()
                        self.anchor = self.lastLocation
                        let ev: [String: Any] = ["isMoving": false, "location": self.lastLocationDict()]
                        self.emit("rnbggeo:motionchange", ev)
                        self.scheduleStationaryTimer()
                    } else if !self.isMoving {
                        // Đã đứng yên từ trước → giữ hẹn giờ tắt GPS luôn chạy.
                        self.scheduleStationaryTimer()
                    }
                } else if !self.isMoving {
                    self.isMoving = true
                    self.stillSince = nil
                    self.anchor = self.lastLocation
                    self.cancelStationaryTimer()
                    if self.enabled { self.startUpdating() }
                    let ev: [String: Any] = ["isMoving": true, "location": self.lastLocationDict()]
                    self.emit("rnbggeo:motionchange", ev)
                }

                let ev: [String: Any] = ["activity": name, "confidence": pct,
                                         "isMoving": self.isMoving]
                self.emit("rnbggeo:activitychange", ev)
            }
        }
    }

    /// Hẹn giờ tắt GPS sau `stopTimeout` phút kể từ lúc phát hiện đứng yên.
    /// KHÔNG thể chờ mẫu GPS tiếp theo: `distanceFilter` khiến máy đứng yên không nhận
    /// được mẫu nào, nên stop-detection cũ (nằm trong handle) không bao giờ chạy
    /// → GPS + mũi tên status bar bật mãi (đúng lỗi tester báo 21/10/2026).
    private func scheduleStationaryTimer() {
        guard stationaryTimer == nil else { return }   // đã hẹn rồi, không đặt lại
        let timeout = (config["stopTimeout"] as? NSNumber)?.doubleValue ?? 5
        guard timeout > 0 else { return }
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.scheduleStationaryTimer() }
            return
        }
        stationaryTimer = Timer.scheduledTimer(withTimeInterval: timeout * 60, repeats: false) {
            [weak self] _ in
            guard let self = self, self.enabled, !self.isMoving else { return }
            self.goStationary(reason: "stopTimeout")
        }
    }

    private func cancelStationaryTimer() {
        stationaryTimer?.invalidate()
        stationaryTimer = nil
    }

    /**
     Đứng yên: hạ GPS xuống chế độ "ngủ đông" và giữ significant-location để đánh thức.

     VÌ SAO KHÔNG TẮT HẲN GPS (mặc định mới, `stationaryLowPowerGps`):
     Android có Activity Recognition + significant-motion để bật lại GPS ngay khi rời
     chỗ. iOS KHÔNG có cơ chế tương đương đánh thức app đang ngủ — chỉ có
     significant-location, mà nó chỉ báo sau ~500 m. Hệ quả đo được ngày 21/10: hai
     máy đi cùng một quãng đường, Android ghi 2 chuyến 3,1 + 2,9 km, iOS chỉ còn
     1,9 km vì mất đoạn đầu mỗi chuyến (và có lúc mất hẳn 101 phút).
     Vì vậy mặc định giữ location ở accuracy thô + distanceFilter 100 m: phát hiện
     di chuyển trong ~100 m, pin gần như không đổi vì không cần fix dày.
     Đặt `stationaryLowPowerGps: false` để quay lại hành vi cũ (tắt hẳn GPS).
     `zeroLossMode: true` vẫn giữ GPS độ chính xác cao liên tục.
     */
    private func goStationary(reason: String) {
        // stop-detection chạy lại mỗi mẫu GPS → chỉ xử lý một lần.
        if reason == "stopTimeout" && !gpsRunning { return }
        if reason == "stopTimeout" && lowPowerGps { return }
        cancelStationaryTimer()
        isMoving = false
        stillSince = Date()
        let preventSuspend = (config["preventSuspend"] as? Bool) ?? false
        if !(config["zeroLossMode"] as? Bool ?? false) {
            if preventSuspend || ((config["stationaryLowPowerGps"] as? Bool) ?? false) {
                // preventSuspend: giữ session location sống (độ chính xác thô) để iOS
                // KHÔNG treo app — JS/heartbeat vẫn chạy. Tốn pin + mũi tên vị trí hiện,
                // đúng cảnh báo của lib gốc ("large and noticeable impact on battery").
                lm.desiredAccuracy = kCLLocationAccuracyHundredMeters
                lm.distanceFilter = max((config["stationaryRadius"] as? NSNumber)?.doubleValue ?? 25, 100)
                lowPowerGps = true
            } else {
                // Tắt GPS hẳn (mũi tên vị trí trên status bar biến mất) và đăng ký
                // "geofence đứng yên" để OS đánh thức app khi rời chỗ — giống lib gốc.
                // PHẢI hạ cờ gpsRunning, nếu không ensureTrackingResumed() (guard
                // !gpsRunning) sẽ tưởng GPS vẫn chạy và KHÔNG bật lại khi có mẫu mới.
                lm.stopUpdatingLocation()
                gpsRunning = false
                lowPowerGps = false
                updateLastKnownRegion()
                // Đứng yên thì không cần giữ app chạy nền (mũi tên vị trí sẽ tắt);
                // geofence "vị trí cuối" + significant-location lo việc đánh thức.
                endBackgroundSession()
            }
        } else {
            lowPowerGps = false
        }
        lm.showsBackgroundLocationIndicator = false
        lm.startMonitoringSignificantLocationChanges()
        // GPS vừa tắt mà vẫn đang trong vùng → hẹn watchdog kiểm tra lại.
        if !gpsRunning { scheduleInsideWatchdog() }
        if reason != "changePace" {
            let p: [String: Any] = ["isMoving": false, "location": lastLocationDict()]
            emit("rnbggeo:motionchange", p)
        }
        diag("service", "TẠM NGHỈ (\(reason)) — " +
            (lowPowerGps ? "GPS thô 100 m" : gpsRunning ? "giữ GPS" : "tắt GPS") +
            ", giữ significant-location")
    }

    /**
     Geofence "vị trí cuối" — mô hình của lib gốc transistorsoft.

     Họ mô tả: "a stationary geofence around the last known position … when the user
     moves beyond the stationary geofence (typically ~200 meters), iOS will completely
     reboot your application in the background". Region sống ở tầng OS nên **sống qua
     cả app bị kill và máy reboot** — đó là lý do gạt app vẫn có thể ghi tiếp.

     Ta cập nhật vùng này ở 2 lúc:
       - khi vào trạng thái đứng yên (goStationary) → vùng quanh chỗ đang đỗ;
       - khi ĐANG ĐI, mỗi `safetyGeofenceIntervalMinutes` phút (mặc định 5) → nhờ vậy
         lúc nào cũng có vùng quanh vị trí hiện tại; nếu app bị kill giữa đường thì
         vẫn còn vùng để OS đánh thức, không phụ thuộc significant-change (500 m–1 km).

     Bán kính tối thiểu 100 m (nhỏ hơn thì OS gần như không đánh giá được). Chiếm 1
     slot trong 20 region/app → syncRegions() phải chừa chỗ và không xoá vùng này.
     */
    private func updateLastKnownRegion() {
        // `lastLocation` là mẫu gần nhất ta đã xử lý. Nếu chưa có (tiến trình vừa được OS
        // đánh thức lại, hoặc hàm này chạy rất sớm ở đường "sắp kết thúc") thì lấy cache
        // của CLLocationManager — thà vùng hơi cũ còn hơn không có vùng nào để OS đánh thức.
        guard let loc = lastLocation ?? lm.location else { return }
        lastRegionUpdate = Date()
        clearLastKnownRegion()
        let radius = max((config["stationaryRadius"] as? NSNumber)?.doubleValue ?? 200, 100)
        let region = CLCircularRegion(center: loc.coordinate, radius: radius,
                                     identifier: BgGeo.lastKnownRegionId)
        region.notifyOnEntry = false
        region.notifyOnExit = true
        lm.startMonitoring(for: region)
        diag("service", "cập nhật geofence vị trí cuối \(Int(radius)) m")
    }

    private func clearLastKnownRegion() {
        for r in lm.monitoredRegions where r.identifier == BgGeo.lastKnownRegionId {
            lm.stopMonitoring(for: r)
        }
    }

    /**
     Watchdog trong vùng (bê từ Android `checkInsideWatchdog`).

     Khi đã tạm dừng vì đứng yên TRONG một vùng (GPS tắt), iOS chỉ còn geofence của hệ
     điều hành để biết "đã ra ngoài" — mà vùng đó có bán kính ≥ 100 m và có thể bị trễ.
     Vì vậy cứ `insideWatchdogMinutes` phút lấy MỘT mẫu thô (không bật GPS liên tục):
     - vẫn trong vùng → tắt location lại và hẹn tiếp;
     - đã ra ngoài  → bật tracking lại, ghi điểm tiếp (evaluateCircles bắn EXIT).
     */
    /// Đang ở trong BẤT KỲ vùng nào: tròn (regionState) hoặc đa giác (rnbggeo_poly_inside).
    /// Trước đây pauseInsideGeofence và watchdog chỉ xét vùng TRÒN → vùng đa giác bị bỏ sót.
    private func isInsideAnyFence() -> Bool {
        if regionState.contains(where: { $0.value }) { return true }
        let poly = defaults.dictionary(forKey: "rnbggeo_poly_inside") ?? [:]
        return poly.contains(where: {
            ($0.value as? Bool) == true || (($0.value as? NSNumber)?.boolValue ?? false)
        })
    }

    private func scheduleInsideWatchdog() {
        insideWatchdogTimer?.invalidate()
        insideWatchdogTimer = nil
        let minutes = (config["insideWatchdogMinutes"] as? NSNumber)?.doubleValue ?? 10
        guard minutes > 0, enabled, !gpsRunning, isInsideAnyFence() else { return }
        log("geofence watchdog: hẹn kiểm tra lại vùng sau \(Int(minutes)) phút")
        insideWatchdogTimer = Timer.scheduledTimer(withTimeInterval: minutes * 60,
                                                   repeats: false) { [weak self] _ in
            self?.runInsideWatchdog()
        }
    }

    private func runInsideWatchdog() {
        guard enabled, !gpsRunning, isInsideAnyFence() else { return }
        // Hẹn NGAY lần kiểm tra kế tiếp TRƯỚC khi lấy mẫu: mẫu GPS đánh thức app rất ngắn,
        // iOS có thể treo lại trước khi kịp hẹn ở cuối hàm (đã gặp 22/10: watchdog chỉ chạy
        // đúng 1 lần rồi im). Nếu lần này phát hiện đã ra ngoài thì startUpdating() sẽ
        // cancelInsideWatchdog() nên không bị hẹn thừa.
        scheduleInsideWatchdog()
        inWatchdog = true
        lm.desiredAccuracy = kCLLocationAccuracyHundredMeters
        lm.requestLocation()
        // Nhả cờ + trả lại độ chính xác cấu hình (không bật GPS liên tục).
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            guard let self = self else { return }
            self.inWatchdog = false
            self.applyLocationSettings()
            if !self.gpsRunning { self.lm.stopUpdatingLocation() }
        }
    }

    private func cancelInsideWatchdog() {
        insideWatchdogTimer?.invalidate()
        insideWatchdogTimer = nil
    }

    /// Cảnh báo (log + diag) khi config có khoá iOS CHƯA hỗ trợ.
    /// Vì chỉ có R&D đọc log, đây là cách chống "im lặng bỏ qua": set khoá mà không thấy
    /// tác dụng thì sẽ thấy ngay một dòng trong log/diag.
    private func warnUnsupportedConfig() {
        var ignored: [String] = []
        if let s = config["schedule"] as? [String], !s.isEmpty { ignored.append("schedule") }
        if ((config["stopAfterElapsedMinutes"] as? NSNumber)?.doubleValue ?? 0) > 0 {
            ignored.append("stopAfterElapsedMinutes")
        }
        if (config["useSignificantChangesOnly"] as? Bool) == true {
            ignored.append("useSignificantChangesOnly")
        }
        if let e = (config["elasticityMultiplier"] as? NSNumber)?.doubleValue, e != 1 {
            ignored.append("elasticityMultiplier")
        }
        if (config["disableElasticity"] as? Bool) == true { ignored.append("disableElasticity") }
        if (config["allowIdenticalLocations"] as? Bool) == true {
            ignored.append("allowIdenticalLocations")
        }
        for k in ["locationTemplate", "geofenceTemplate", "method"] where config[k] != nil {
            ignored.append(k)
        }
        guard !ignored.isEmpty else { return }
        diag("config", "iOS CHƯA hỗ trợ, đang bỏ qua: " + ignored.joined(separator: ", "))
    }

    private func startHeartbeat() {
        heartbeatTimer?.invalidate()
        let interval = max((config["heartbeatInterval"] as? NSNumber)?.doubleValue ?? 60, 15)
        // Bắn "alive" NGAY khi bắt đầu theo dõi: đọc /diag là biết tiến trình vừa khởi động
        // và cấu hình (gps/pending/odom) thế nào, không phải chờ 15 phút.
        postAliveIfDue()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            guard let self = self, self.enabled else { return }
            self.emit("rnbggeo:heartbeat", [
                "location": self.lastLocationDict(),
                "odometer": self.odometer,
                "battery": BgGeo.batteryLevel(),
                "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            ])
            self.postAliveIfDue()
            self.checkProviderSilence()
        }
    }

    /**
     WATCHDOG "NHÀ CUNG CẤP VỊ TRÍ IM LẶNG" — bản iOS.

     Chỉ chạy được khi app còn sống (iOS treo app thì Timer không chạy — đó là giới hạn nền
     của hệ điều hành, xem mục 13.26/13.27 trong tài liệu bàn giao). Khi app CÒN sống, đang
     được cho là ĐANG ĐI (`isMoving`) và GPS đang chạy mà không nhận được mẫu nào trong
     `providerSilenceMinutes` phút thì:
       1. ghi diag `gap` lên server — nhìn thấy từ xa, không cần mở Xcode;
       2. "đá" lại: `startUpdatingLocation()` + `requestLocation()` một lần.

     Trước đây iOS chỉ ghi `gap` khi MẪU KẾ TIẾP tới (tức là sau khi đã mất đoạn) — watchdog
     này báo và tự chữa NGAY trong lúc đang im lặng.
     */
    private func checkProviderSilence() {
        let minutes = (config["providerSilenceMinutes"] as? NSNumber)?.doubleValue ?? 3
        guard minutes > 0, enabled, isMoving, gpsRunning else { return }
        // KHÔNG "đá" khi đang ở TRONG một vùng geofence: đứng yên trong nhà/công ty thì không có
        // mẫu là ĐÚNG, và khi rời vùng thì geofence EXIT của hệ điều hành sẽ đánh thức app.
        // (Đo thật 23/10 trên Android: đỗ xe ở nhà bị watchdog đá oan 3 lần.)
        if isInsideAnyFence() { return }
        guard lastProviderSampleAt != .distantPast else {
            lastProviderSampleAt = Date()
            return
        }
        let silence = Date().timeIntervalSince(lastProviderSampleAt)
        guard silence >= minutes * 60 else { return }
        if lastProviderKickAt != .distantPast,
           Date().timeIntervalSince(lastProviderKickAt) < 300 { return }
        lastProviderKickAt = Date()
        lastProviderSampleAt = Date()
        providerKicks += 1
        diag("gap", "KHÔNG có mẫu vị trí trong \(Int(silence / 60)) phút (đang di chuyển, " +
            "gpsRunning=1) → đá lại provider (lần \(providerKicks))")
        lm.startUpdatingLocation()
        lm.requestLocation()
    }

    /**
     Diag "alive" ~15 phút/lần (Android: TrackingService.heartbeat) để đọc /diag là biết
     app còn sống hay đã bị iOS treo/giết. Lần đầu gửi ngay ở nhịp tim đầu tiên (~60 s
     sau khi bật) để không phải chờ 15 phút mới có dấu hiệu đầu tiên.
     */
    private func postAliveIfDue() {
        let minutes = (config["aliveIntervalMinutes"] as? NSNumber)?.doubleValue ?? 15
        guard minutes > 0 else { return }
        if lastAliveAt != .distantPast, Date().timeIntervalSince(lastAliveAt) < minutes * 60 {
            return
        }
        lastAliveAt = Date()
        diag("alive", String(format: "bat=%.0f%% charging=%@ pending=%d odom=%.0fm moving=%@ act=%@ gps=%d",
                             BgGeo.batteryLevel() * 100,
                             BgGeo.isCharging() ? "true" : "false",
                             db.pendingCount(), odometer,
                             isMoving ? "true" : "false", activity, gpsRunning ? 1 : 0))
    }

    // MARK: - CLLocationManagerDelegate

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard !locations.isEmpty else { return }
        // Watchdog "provider im lặng" đếm lại từ đây (provider CÓ giao mẫu).
        lastProviderSampleAt = Date()
        // iOS KHÔNG giao từng mẫu một khi app vừa bị treo: tài liệu Apple ghi rõ "the system
        // enqueues location updates and delivers them when the app runs again". Trước đây
        // ta chỉ dùng `locations.last` → toàn bộ quãng đường trong lô bị ném đi, bản đồ chỉ
        // còn 1 đoạn đầu mỗi lần được đánh thức (đúng triệu chứng 22/10: 5 phút im lặng,
        // cách nhau 1.501 m). Nay xử lý TẤT CẢ, theo thứ tự thời gian.
        let ordered = locations.sorted { $0.timestamp < $1.timestamp }
        if ordered.count > 1 {
            let span = ordered[ordered.count - 1].timestamp.timeIntervalSince(ordered[0].timestamp)
            diag("batch", String(format: "nhận lô %d mẫu trong %.0f giây — iOS giao bù các mẫu đã xếp hàng",
                                 ordered.count, span))
        }
        for (i, loc) in ordered.enumerated() {
            // Chỉ lần cuối mới gọi sync mạng, tránh N request HTTP cho N mẫu trong lô.
            handle(loc, autoSync: i == ordered.count - 1)
        }
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        emit("rnbggeo:providerchange", providerState())
    }

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let st = providerState()
        emit("rnbggeo:providerchange", st)
        emit("rnbggeo:authorization", st)
        if enabled && isAuthorized { startUpdating() }
    }

    public func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        handleRegion(region.identifier, enter: true)
    }

    public func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        handleRegion(region.identifier, enter: false)
    }

    /**
     Kết quả `requestState(for:)` (gọi trong BgGeoGeofences.syncRegions) và các lần iOS tự
     cập nhật trạng thái vùng mà không có sự kiện vượt biên. Nhờ đây mà "đang ở trong vùng
     lúc mở app / lúc đăng ký vùng" vẫn có ENTER + thông báo, thay vì im lặng mãi.
     */
    public func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState,
                                for region: CLRegion) {
        guard state == .inside, region.identifier != BgGeo.lastKnownRegionId else { return }
        handleRegion(region.identifier, enter: true, initial: true)
    }

    private func handle(_ loc: CLLocation, autoSync: Bool = true) {
        // Mẫu đến khi GPS thường đang tắt = vừa được đánh thức → bật GPS lại ngay,
        // nếu không cả chuyến đi chỉ còn 1 điểm mỗi ~500 m (xem 21/10).
        // Mẫu từ watchdog trong vùng thì KHÔNG bật GPS liên tục. Đọc & XOÁ cờ ngay tại đây
        // (không phải trong khối 8 s): nếu app bị treo trước khi khối đó chạy, cờ sẽ kẹt
        // true và chặn việc bật GPS ở mẫu thật kế tiếp.
        let fromWatchdog = inWatchdog
        inWatchdog = false
        if !fromWatchdog { ensureTrackingResumed() }
        let maxAcc = (config["trackingAccuracyThreshold"] as? NSNumber)?.doubleValue ?? 100
        if loc.horizontalAccuracy > maxAcc && lastLocation != nil {
            log(String(format: "bỏ mẫu acc=%.0f m (ngưỡng %.0f)", loc.horizontalAccuracy, maxAcc))
            emit("rnbggeo:locationfilter", ["accepted": false, "reason": "accuracy",
                                            "accuracy": loc.horizontalAccuracy])
            return
        }

        if let prev = lastLocation {
            let dt = loc.timestamp.timeIntervalSince(prev.timestamp)
            let delta = loc.distance(from: prev)
            // Khoảng trống > 2 phút VÀ có di chuyển thật: đây là chỗ dữ liệu bị mất, ghi lại
            // để lần sau còn truy được nguyên nhân thay vì chỉ thấy đường đứt trên bản đồ.
            // (Bỏ qua trường hợp đứng yên trong geofence: mẫu bị chủ động không ghi nên
            // khoảng trống thời gian dài mà quãng đường = 0 — không phải mất dữ liệu.)
            if dt >= 120 && delta >= 20 {
                diag("gap", String(format: "im lặng %.0f giây giữa 2 mẫu, cách nhau %.0f m " +
                                   "(app có thể bị iOS treo → mẫu bị xếp hàng)", dt, delta))
            }
            if delta >= 0 && delta <= 5000 { odometer += delta }
            defaults.set(odometer, forKey: BgGeo.odoKey)
        }

        // GPS báo đang chạy → chắc chắn di chuyển, kể cả khi cảm biến chưa kịp đổi.
        if !isMoving, loc.speed >= 0.7 {
            isMoving = true
            stillSince = nil
            anchor = loc
            cancelStationaryTimer()
            startUpdating()
            emit("rnbggeo:motionchange", ["isMoving": true, "location":
                BgGeo.locationDict(loc, uuid: UUID().uuidString, odometer: odometer,
                                   activity: activity, isMoving: true)])
        }

        // Không có dữ liệu cảm biến (simulator) hoặc CoreMotion chưa phân loại được
        // → suy nhãn hoạt động từ tốc độ GPS để Dashboard không hiện "unknown" mãi.
        if activity == "unknown" {
            let derived = loc.speed >= 5 ? "in_vehicle" : (loc.speed >= 0.7 ? "on_foot" : "still")
            activity = derived
            emit("rnbggeo:activitychange", ["activity": derived, "confidence": 60,
                                            "isMoving": isMoving])
        }

        let radius = max((config["stationaryRadius"] as? NSNumber)?.doubleValue ?? 25, 25)
        let timeout = (config["stopTimeout"] as? NSNumber)?.doubleValue ?? 5
        if !isMoving {
            if let a = anchor, loc.distance(from: a) > radius {
                isMoving = true
                stillSince = nil
                cancelStationaryTimer()
                startUpdating()
                let movingPayload: [String: Any] = ["isMoving": true, "location":
                    BgGeo.locationDict(loc, uuid: UUID().uuidString, odometer: odometer,
                                       activity: activity, isMoving: true)]
                emit("rnbggeo:motionchange", movingPayload)
            } else if anchor == nil {
                anchor = loc
            } else if loc.speed < 0.5 {
                // Đã đứng yên → chạy stop-detection để TẮT GPS sau stopTimeout phút.
                // Trước đây nhánh này chỉ chạy khi isMoving = true, nên hễ cảm biến
                // set isMoving = false là GPS + mũi tên status bar bật mãi.
                if stillSince == nil { stillSince = Date() }
                scheduleStationaryTimer()   // dự phòng khi không có dữ liệu cảm biến
                if let s = stillSince, Date().timeIntervalSince(s) > timeout * 60 {
                    anchor = loc
                    goStationary(reason: "stopTimeout")
                }
            } else {
                stillSince = nil
                anchor = loc
            }
        } else if loc.speed < 0.5, let a = anchor, loc.distance(from: a) < radius {
            if stillSince == nil { stillSince = Date() }
            if let s = stillSince, Date().timeIntervalSince(s) > timeout * 60 {
                anchor = loc
                goStationary(reason: "stopTimeout")
            }
        } else {
            stillSince = nil
            anchor = loc
        }

        // Đang đi: dời geofence "vị trí cuối" mỗi N phút để lúc nào cũng có vùng quanh
        // vị trí hiện tại. Nếu app bị kill giữa đường, OS vẫn có vùng để đánh thức
        // (mô hình lib gốc: "geofence around the last known position").
        let regionInterval = (config["safetyGeofenceIntervalMinutes"] as? NSNumber)?.doubleValue ?? 5
        if isMoving, regionInterval > 0,
           Date().timeIntervalSince(lastRegionUpdate) >= regionInterval * 60 {
            updateLastKnownRegion()
        }

        // Đang "ngủ đông" mà đã phát hiện di chuyển → trả GPS về Best + 10 m NGAY, để
        // phần còn lại của chuyến đi được ghi dày như Android.
        if lowPowerGps && isMoving {
            applyLocationSettings()
            lowPowerGps = false
            diag("service", "phát hiện di chuyển → GPS về chế độ chính xác cao")
        }

        // CLLocation.speed = -1 khi không tính được (Wi-Fi, mô phỏng) và ta đang kẹp về 0
        // → bản ghi toàn speed = 0 dù xe chạy 12 m/s. Suy tốc độ từ khoảng cách để bản
        // đồ phân loại đúng "kiểu đi" và app hiện đúng tốc độ. Phải tính TRƯỚC khi gán
        // lastLocation, nếu không prev chính là mẫu hiện tại → khoảng cách = 0.
        var effectiveSpeed = loc.speed
        if effectiveSpeed < 0.3, let prev = lastLocation {
            let dts = loc.timestamp.timeIntervalSince(prev.timestamp)
            let ds = loc.distance(from: prev)
            if dts > 0.5, ds >= 3, ds / dts < 60 { effectiveSpeed = ds / dts }
        }

        lastLocation = loc
        if let cb = pendingFix { cb(loc); pendingFix = nil }

        let dict = BgGeo.locationDict(loc, uuid: UUID().uuidString, odometer: odometer,
                                      activity: activity, isMoving: isMoving,
                                      speedOverride: effectiveSpeed)
        // pauseInsideGeofence (bê nguyên hành vi Android, TrackingService):
        //   - Đứng yên TRONG vùng đã biết → KHÔNG ghi điểm (tránh chùm điểm GPS trôi
        //     trong nhà/văn phòng). Vẫn cập nhật lastLocation + vẫn chấm geofence ở dưới
        //     để bắt EXIT, nên ra khỏi vùng là ghi tiếp.
        //   - ĐANG ĐI trong vùng → vẫn ghi (nếu bỏ thì đoạn đầu/cuối mỗi chuyến bị đứt).
        // Android đo được: trước đây bỏ mọi mẫu trong vùng nên mất ~200 m đầu và cuối.
        // LƯU Ý: phải xét cả vùng TRÒN (regionState) và ĐA GIÁC (rnbggeo_poly_inside) —
        // xem isInsideAnyFence().
        let insideAnyFence = isInsideAnyFence()
        let pausedInsideFence = ((config["pauseInsideGeofence"] as? Bool) ?? false)
            && !isMoving && loc.speed < 0.7
            && insideAnyFence
        if pausedInsideFence {
            log(String(format: "loc BỎ (đứng yên trong geofence, không ghi điểm) %.5f,%.5f spd=%.2f",
                       loc.coordinate.latitude, loc.coordinate.longitude, max(loc.speed, 0)))
            emit("rnbggeo:locationfilter", ["accepted": false, "reason": "geofence",
                                            "accuracy": loc.horizontalAccuracy])
        } else {
            db.insert(dict)
            insertCount += 1
            // Áp chính sách lưu trữ theo lô 20 mẫu (không chạy DELETE mỗi mẫu).
            if insertCount % 20 == 0 { applyRetention() }
            emit("rnbggeo:location", dict)
            log(String(format: "loc %.6f,%.6f acc=%.0f v=%.2f moving=%d odo=%.0f gps=%d",
                       loc.coordinate.latitude, loc.coordinate.longitude, loc.horizontalAccuracy,
                       max(loc.speed, 0), isMoving ? 1 : 0, odometer, gpsRunning ? 1 : 0))
        }

        // Đứng yên → hẹn giờ tắt GPS. Phải làm ở ĐÂY vì máy đứng yên chỉ có đúng
        // mẫu GPS đầu tiên (distanceFilter chặn các mẫu sau) → không chờ được mẫu kế.
        if !isMoving && loc.speed < 0.5 {
            scheduleStationaryTimer()
        } else if isMoving {
            cancelStationaryTimer()
        }

        // Đa giác: chấm điểm cục bộ mỗi mẫu GPS (iOS không có vùng đa giác native).
        evaluatePolygons(loc)
        // Vùng TRÒN: bán kính thật có thể nhỏ hơn bán kính đánh thức của iOS (≥100 m)
        // → phải chấm trên từng mẫu (xem evaluateCircles).
        evaluateCircles(loc)

        if autoSync, (config["autoSync"] as? Bool ?? true),
           !((config["url"] as? String) ?? "").isEmpty {
            uploader.sync(config: config) { _ in }
        }
        // TRƯỚC ĐÂY gọi ở MỌI mẫu GPS → mỗi lần đều stopMonitoring + startMonitoring lại
        // toàn bộ vùng, nên lúc đi vào vùng iOS không thấy "vượt biên" → KHÔNG BAO GIỜ bắn
        // ENTER/EXIT (đo 22/10: vào Cty 07:34:57 mà không có sự kiện, không thông báo).
        if BgGeoGeofences.shared.needsSync {
            BgGeoGeofences.shared.syncRegions(with: lm)
            pruneRegionState()
        }
    }

    // MARK: - Helper tạo payload

    /**
     * Kiểm tra các geofence ĐA GIÁC với mẫu GPS vừa nhận và bắn ENTER/EXIT khi
     * trạng thái đổi. Trạng thái trong/ngoài lưu ở UserDefaults để một lần EXIT
     * vẫn đúng sau khi app bị hệ thống khởi động lại.
     */
    private func evaluatePolygons(_ loc: CLLocation) {
        let polys = BgGeoGeofences.shared.all().filter { ($0["polygon"] as? Bool ?? false) }
        guard !polys.isEmpty else { return }
        var state = (defaults.dictionary(forKey: "rnbggeo_poly_inside") as? [String: Bool]) ?? [:]
        var changed = false
        for g in polys {
            guard let id = g["identifier"] as? String,
                  let verts = g["vertices"] as? [[Double]], verts.count >= 3 else { continue }
            let inside = BgGeoGeofences.pointInPolygon(
                loc.coordinate.latitude, loc.coordinate.longitude, verts)
            let was = state[id] ?? false
            guard inside != was else { continue }
            state[id] = inside
            changed = true
            var payload = g
            payload["action"] = inside ? 1 : 2
            payload["actionName"] = inside ? "ENTER" : "EXIT"
            payload["identifier"] = id
            payload["polygon"] = true
            payload["location"] = BgGeo.locationDict(
                loc, uuid: UUID().uuidString, odometer: odometer,
                activity: activity, isMoving: isMoving)
            // Như nhánh vòng tròn: thiếu timestamp là server/viewer mất mốc thời gian.
            payload["timestamp"] = Int(Date().timeIntervalSince1970 * 1000)
            emit("rnbggeo:geofence", payload)
            postGeofenceEvent(payload)
        }
        if changed { defaults.set(state, forKey: "rnbggeo_poly_inside") }
    }

    /**
     * Kiểm tra các geofence TRÒN với mẫu GPS vừa nhận — phần quyết định ENTER/EXIT thật.

     * Vì sao cần: iOS chỉ theo dõi vùng ở tầng OS với bán kính ≥ 100 m (`CLCircularRegion`
     * "applies time and distance criteria" nên vùng vài mét gần như không bao giờ được báo).
     * Bán kính người dùng chọn (vd 10 m) vì vậy phải chấm trên từng mẫu GPS, nếu không thì
     * "chọn 10 m" sẽ thành "báo ở 100 m".
     *
     * Có trễ (hysteresis) để GPS trôi không làm nhảy ENTER/EXIT liên tục: vào khi
     * d ≤ r, ra khi d > r + max(15 m, sai số hiện tại).
     */
    private func evaluateCircles(_ loc: CLLocation) {
        let circles = BgGeoGeofences.shared.all().filter { !($0["polygon"] as? Bool ?? false) }
        guard !circles.isEmpty else { return }
        for g in circles {
            guard let id = g["identifier"] as? String,
                  let lat = (g["latitude"] as? NSNumber)?.doubleValue,
                  let lng = (g["longitude"] as? NSNumber)?.doubleValue,
                  let radius = (g["radius"] as? NSNumber)?.doubleValue else { continue }
            let d = loc.distance(from: CLLocation(latitude: lat, longitude: lng))
            let inside = regionState[id] ?? false
            if !inside && d <= radius {
                handleRegion(id, enter: true, fromSample: true)
            } else if inside && d > radius + max(15, loc.horizontalAccuracy) {
                handleRegion(id, enter: false, fromSample: true)
            }
        }
    }

    static func locationDict(_ loc: CLLocation, uuid: String, odometer: Double,
                             activity: String, isMoving: Bool,
                             speedOverride: Double? = nil) -> [String: Any] {
        let speed = max(speedOverride ?? loc.speed, 0)
        return [
            "uuid": uuid,
            "timestamp": Int(loc.timestamp.timeIntervalSince1970 * 1000),
            "event": "location",
            "is_moving": speed > 0.5 && isMoving,
            // Định danh thiết bị — server/viewer dùng để lọc theo máy.
            // Dùng mã LƯU Ở KEYCHAIN, không dùng identifierForVendor trực tiếp:
            // IDFV đổi sau mỗi lần cài lại app → một máy hiện thành nhiều thiết bị.
            "device_id": BgGeo.stableDeviceId(),
            "device_name": BgGeo.deviceDisplayName,
            "device_platform": "ios",
            "coords": [
                "latitude": loc.coordinate.latitude,
                "longitude": loc.coordinate.longitude,
                "speed": speed,
                "heading": loc.course >= 0 ? loc.course : 0,
                "accuracy": max(loc.horizontalAccuracy, 0),
                "altitude": loc.altitude,
                "altitude_accuracy": max(loc.verticalAccuracy, 0),
            ],
            "activity": ["type": activity, "confidence": 100],
            "battery": ["level": batteryLevel(), "is_charging": isCharging()],
            "odometer": odometer,
            "mock": false,
        ]
    }

    static func batteryLevel() -> Double {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let l = UIDevice.current.batteryLevel
        return l < 0 ? 0 : Double(l)
    }

    static func isCharging() -> Bool {
        let s = UIDevice.current.batteryState
        return s == .charging || s == .full
    }

    // MARK: - API dữ liệu

    @objc func getLocations(_ resolve: @escaping RCTPromiseResolveBlock,
                            rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(jsonArray(db.history(limit: BgGeo.historyLimit)))
    }

    @objc func getCount(_ resolve: @escaping RCTPromiseResolveBlock,
                        rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(db.pendingCount())
    }

    @objc func sync(_ resolve: @escaping RCTPromiseResolveBlock,
                    rejecter reject: @escaping RCTPromiseRejectBlock) {
        uploader.sync(config: config) { ok in resolve(ok) }
    }

    @objc func destroyLocations(_ resolve: @escaping RCTPromiseResolveBlock,
                                rejecter reject: @escaping RCTPromiseRejectBlock) {
        db.deleteAll()
        resolve(true)
    }

    @objc func destroyLocation(_ uuid: String,
                               resolver resolve: @escaping RCTPromiseResolveBlock,
                               rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(db.deleteOne(uuid))
    }

    @objc func insertLocation(_ location: NSDictionary,
                              resolver resolve: @escaping RCTPromiseResolveBlock,
                              rejecter reject: @escaping RCTPromiseRejectBlock) {
        var dict = (location as? [String: Any]) ?? [:]
        let uuid = (dict["uuid"] as? String) ?? UUID().uuidString
        dict["uuid"] = uuid
        if dict["timestamp"] == nil {
            dict["timestamp"] = Int(Date().timeIntervalSince1970 * 1000)
        }
        resolve(db.insert(dict) ? uuid : "")
    }

    @objc func getOdometer(_ resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(odometer)
    }

    @objc func setOdometer(_ value: Double,
                           resolver resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
        odometer = value
        defaults.set(value, forKey: BgGeo.odoKey)
        resolve(true)
    }

    @objc func resetOdometer(_ resolve: @escaping RCTPromiseResolveBlock,
                             rejecter reject: @escaping RCTPromiseRejectBlock) {
        odometer = 0
        defaults.set(0.0, forKey: BgGeo.odoKey)
        resolve(true)
    }

    // MARK: - Quyền & trạng thái thiết bị

    private func requestPermissionInternal() {
        if lm.authorizationStatus == .notDetermined {
            lm.requestAlwaysAuthorization()
        }
    }

    func providerState() -> [String: Any] {
        let status: Int
        switch lm.authorizationStatus {
        case .notDetermined: status = 0
        case .restricted: status = 1
        case .denied: status = 2
        case .authorizedAlways: status = 3
        case .authorizedWhenInUse: status = 4
        @unknown default: status = 0
        }
        return [
            "enabled": CLLocationManager.locationServicesEnabled(),
            "gps": CLLocationManager.locationServicesEnabled(),
            "network": true,
            "status": status,
            "accuracyAuthorization": lm.accuracyAuthorization == .fullAccuracy ? 1 : 0,
            "authorized": isAuthorized,
        ]
    }

    @objc func hasPermission(_ resolve: @escaping RCTPromiseResolveBlock,
                             rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve([
            "fine": isAuthorized,
            "background": lm.authorizationStatus == .authorizedAlways,
        ])
    }

    @objc func requestPermission(_ resolve: @escaping RCTPromiseResolveBlock,
                                 rejecter reject: @escaping RCTPromiseRejectBlock) {
        requestPermissionInternal()
        resolve(jsonString(providerState()))
    }

    @objc func getProviderState(_ resolve: @escaping RCTPromiseResolveBlock,
                                rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(jsonString(providerState()))
    }

    @objc func getDeviceInfo(_ resolve: @escaping RCTPromiseResolveBlock,
                             rejecter reject: @escaping RCTPromiseRejectBlock) {
        let d = UIDevice.current
        resolve(jsonString([
            "platform": "ios",
            "model": d.model,
            // `name` PHẢI TRÙNG ĐÚNG giá trị gửi lên server trong `device_name`
            // (BgGeo.deviceDisplayName = "iPhone" / "iPhone (Simulator)").
            // Trước đây trả `d.name` (tên người dùng đặt, ví dụ "iPhone của Hoa") → app đóng dấu
            // tên KHÁC với tên trong bản ghi vị trí của cùng một device_id ⇒ server hiện 2 tên
            // cho cùng một máy. Sửa 27/10/2026.
            "name": BgGeo.deviceDisplayName,
            "manufacturer": "Apple",
            "version": d.systemVersion,
            // ⚠️ KHÔNG khai lại "name" ở đây: Swift TRAP khi dictionary literal trùng khoá
            // (`Fatal error: Dictionary literal contains duplicate keys`, SIGTRAP) — đã gây crash
            // thật 23 lần (27/10 14:08 → 28/10 07:41) ngay khi app gọi getDeviceInfo lúc khởi động.
            // Dòng `"name": d.name` cũ đã bị thay bằng `"name": BgGeo.deviceDisplayName` ở trên.
            // Mã ổn định đúng là mã đang gửi lên server (xem stableDeviceId()).
            "device_id": BgGeo.stableDeviceId(),
            "identifierForVendor": d.identifierForVendor?.uuidString ?? "",
        ]))
    }

    @objc func getSensors(_ resolve: @escaping RCTPromiseResolveBlock,
                          rejecter reject: @escaping RCTPromiseRejectBlock) {
        var arr: [[String: Any]] = [
            ["name": "gps", "available": CLLocationManager.locationServicesEnabled()],
            ["name": "significant-motion", "available": true],
        ]
        if CMMotionActivityManager.isActivityAvailable() {
            arr.append(["name": "activity-recognition", "available": true])
        }
        resolve(jsonArray(arr))
    }

    @objc func isPowerSaveMode(_ resolve: @escaping RCTPromiseResolveBlock,
                               rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(ProcessInfo.processInfo.isLowPowerModeEnabled)
    }

    @objc func getConnectivityInfo(_ resolve: @escaping RCTPromiseResolveBlock,
                                   rejecter reject: @escaping RCTPromiseRejectBlock) {
        // Trước đây trả cứng {connected: true} → app hiện "có mạng" kể cả khi mất mạng.
        resolve(jsonString(["connected": netConnected, "type": netType]))
    }

    /// Theo dõi mạng thật bằng NWPathMonitor; bắn `rnbggeo:connectivity` khi đổi trạng thái
    /// (giống ConnectivityReceiver của Android).
    private func startConnectivityMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let connected = path.status == .satisfied
            let type: String
            if path.usesInterfaceType(.wifi) { type = "wifi" }
            else if path.usesInterfaceType(.cellular) { type = "cellular" }
            else if path.usesInterfaceType(.wiredEthernet) { type = "ethernet" }
            else { type = connected ? "other" : "none" }
            let changed = (self.netConnected != connected) || (self.netType != type)
            self.netConnected = connected
            self.netType = type
            if changed {
                self.log("connectivity: connected=\(connected ? 1 : 0) type=\(type)")
                DispatchQueue.main.async {
                    self.emit("rnbggeo:connectivity", ["connected": connected, "type": type])
                }
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "rnbggeo.net"))
    }

    @objc func startBackgroundTask(_ resolve: @escaping RCTPromiseResolveBlock,
                                   rejecter reject: @escaping RCTPromiseRejectBlock) {
        var id: UIBackgroundTaskIdentifier = .invalid
        id = UIApplication.shared.beginBackgroundTask(withName: "rnbggeo") {
            if id != .invalid { UIApplication.shared.endBackgroundTask(id) }
        }
        backgroundTasks.append(id)
        resolve(Int(id.rawValue))
    }

    @objc func endBackgroundTask(_ taskId: Double,
                                 resolver resolve: @escaping RCTPromiseResolveBlock,
                                 rejecter reject: @escaping RCTPromiseRejectBlock) {
        let raw = Int(taskId)
        if let id = backgroundTasks.first(where: { $0.rawValue == raw }) {
            UIApplication.shared.endBackgroundTask(id)
            backgroundTasks.removeAll { $0.rawValue == raw }
        }
        resolve(true)
    }

    // MARK: - Geofence

    @objc func addGeofence(_ geofence: NSDictionary,
                           resolver resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard let g = geofence as? [String: Any] else { resolve(false); return }
        let ok = BgGeoGeofences.shared.add(g)
        BgGeoGeofences.shared.syncRegions(with: lm)
        emit("rnbggeo:geofenceschange", ["geofences": BgGeoGeofences.shared.all()])
        resolve(ok)
    }

    @objc func addGeofences(_ geofences: NSArray,
                            resolver resolve: @escaping RCTPromiseResolveBlock,
                            rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard let list = geofences as? [[String: Any]] else { resolve(false); return }
        list.forEach { _ = BgGeoGeofences.shared.add($0) }
        BgGeoGeofences.shared.syncRegions(with: lm)
        emit("rnbggeo:geofenceschange", ["geofences": BgGeoGeofences.shared.all()])
        resolve(true)
    }

    @objc func removeGeofence(_ identifier: String,
                              resolver resolve: @escaping RCTPromiseResolveBlock,
                              rejecter reject: @escaping RCTPromiseRejectBlock) {
        let ok = BgGeoGeofences.shared.remove([identifier])
        BgGeoGeofences.shared.syncRegions(with: lm)
        // Xoá luôn trạng thái trong/ngoài của vùng vừa xoá (nếu không sẽ nằm lại mãi).
        pruneRegionState()
        emit("rnbggeo:geofenceschange", ["geofences": BgGeoGeofences.shared.all()])
        resolve(ok)
    }

    @objc func removeGeofences(_ identifiers: NSArray,
                               resolver resolve: @escaping RCTPromiseResolveBlock,
                               rejecter reject: @escaping RCTPromiseRejectBlock) {
        var ok = false
        if let ids = identifiers as? [String] { ok = BgGeoGeofences.shared.remove(ids) }
        BgGeoGeofences.shared.syncRegions(with: lm)
        pruneRegionState()
        emit("rnbggeo:geofenceschange", ["geofences": BgGeoGeofences.shared.all()])
        resolve(ok)
    }

    @objc func getGeofences(_ resolve: @escaping RCTPromiseResolveBlock,
                            rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(jsonArray(BgGeoGeofences.shared.all()))
    }

    @objc func getGeofence(_ identifier: String,
                           resolver resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
        guard let g = BgGeoGeofences.shared.one(identifier) else { resolve(nil); return }
        resolve(jsonString(g))
    }

    @objc func geofenceExists(_ identifier: String,
                              resolver resolve: @escaping RCTPromiseResolveBlock,
                              rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(BgGeoGeofences.shared.one(identifier) != nil)
    }

    @objc func getGeofenceStats(_ resolve: @escaping RCTPromiseResolveBlock,
                                rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(jsonString(["count": BgGeoGeofences.shared.all().count,
                            "max": BgGeo.maxGeofences]))
    }

    /// Gọi từ BgGeoGeofences khi vùng vào/ra, hoặc từ evaluateCircles() khi chấm mẫu GPS.
    func handleRegion(_ identifier: String, enter: Bool, initial: Bool = false,
                      fromSample: Bool = false) {
        // Geofence "vị trí cuối": không phải vùng của người dùng, chỉ dùng để OS đánh
        // thức app khi máy rời khỏi chỗ đã dừng/bị kill. Không bắn sự kiện geofence.
        if identifier == BgGeo.lastKnownRegionId {
            clearLastKnownRegion()
            if !enter {
                ensureTrackingResumed()
                diag("service", "rời geofence đứng yên → bật GPS")
            }
            return
        }
        // Đa giác: vùng tròn bao quanh chỉ để hệ thống đánh thức app.
        // ENTER/EXIT thật do point-in-polygon tính trên từng mẫu GPS.
        if let g = BgGeoGeofences.shared.one(identifier), (g["polygon"] as? Bool ?? false) {
            return
        }
        guard let fence = BgGeoGeofences.shared.one(identifier) else { return }
        // VÙNG TRÒN: iOS chỉ theo dõi ở tầng OS với bán kính ≥ 100 m (nativeRadius), còn
        // bán kính người dùng chọn có thể nhỏ hơn nhiều (10 m). Vì vậy:
        //   - tín hiệu native (didEnterRegion / didDetermineState) chỉ là "đã tới gần":
        //     phải đo khoảng cách thật, chưa vào trong bán kính thật thì KHÔNG bắn ENTER,
        //     chỉ bật GPS để chấm điểm chính xác;
        //   - native EXIT thì chắc chắn đã ra ngoài (bán kính native ≥ bán kính thật);
        //   - còn lại do evaluateCircles() quyết định trên từng mẫu GPS.
        if !fromSample {
            if enter {
                let radius = (fence["radius"] as? NSNumber)?.doubleValue ?? 100
                let lat = (fence["latitude"] as? NSNumber)?.doubleValue
                let lng = (fence["longitude"] as? NSNumber)?.doubleValue
                if let loc = lastLocation, let lat = lat, let lng = lng {
                    let d = loc.distance(from: CLLocation(latitude: lat, longitude: lng))
                    if d > radius {
                        ensureTrackingResumed()
                        log(String(format: "geofence %@: vào vùng đệm %.0f m (bán kính thật %.0f m) → bật GPS chấm điểm",
                                   identifier, d, radius))
                        return
                    }
                } else if lastLocation == nil {
                    // Chưa có mẫu nào → chưa biết trong/ngoài, bật GPS để đo.
                    ensureTrackingResumed()
                    return
                }
            }
        }
        // Chống bắn lặp: `requestState()` + `didEnterRegion` + evaluateCircles() có thể
        // cùng trả về một trạng thái, và iOS hay giao lại state sau mỗi lần đánh thức.
        // `unknown` = lần đầu biết trạng thái vùng này (vừa cài app, vừa tạo vùng): khi đó
        // "đang ở trong vùng" KHÔNG phải "vừa đến" — vẫn ghi nhận trạng thái (để JS biết
        // đang trong vùng) nhưng đánh dấu initial ⇒ không có thông báo.
        let unknown = regionState[identifier] == nil
        if let prev = regionState[identifier], prev == enter { return }
        regionState[identifier] = enter
        saveRegionState()
        let g = fence
        // Ra khỏi vùng = vừa rời nhà/công ty → BẬT GPS NGAY. Đây là tín hiệu đánh thức
        // đáng tin nhất trên iOS: không phải chờ significant-location ~500 m mới bắt đầu
        // ghi, nên không mất đoạn đầu của chuyến đi.
        if !enter { ensureTrackingResumed() }
        // Đang ở trong vùng khi vừa đăng ký vùng (geofenceInitialTriggerEntry) cũng coi
        // là ENTER — giống Android — nhưng chỉ khi config cho phép.
        if (initial || unknown) && !((config["geofenceInitialTriggerEntry"] as? Bool) ?? true) {
            return
        }
        var payload = g
        payload["action"] = enter ? 1 : 2
        payload["actionName"] = enter ? "ENTER" : "EXIT"
        payload["identifier"] = identifier
        // BẮT BUỘC có timestamp: thiếu khoá này thì server lưu sự kiện không có mốc thời
        // gian, viewer không lọc được theo ngày và không vẽ được điểm. Đo 23/10: 16 bản ghi
        // sự kiện geofence của iPhone nằm trên server đều KHÔNG có timestamp (Android có).
        payload["timestamp"] = Int(Date().timeIntervalSince1970 * 1000)
        if initial || unknown { payload["initial"] = true }
        if let loc = lastLocation {
            payload["location"] = BgGeo.locationDict(loc, uuid: UUID().uuidString,
                                                     odometer: odometer, activity: activity,
                                                     isMoving: isMoving)
        }
        diag("geofence", "\(enter ? "ENTER" : "EXIT") \(payload["name"] as? String ?? identifier)" +
            (initial || unknown ? " (đang ở trong vùng lúc đăng ký)" : ""))
        emit("rnbggeo:geofence", payload)
        notifyGeofence(payload, enter: enter, initial: initial || unknown)
        postGeofenceEvent(payload)
    }

    /// Đọc trạng thái trong/ngoài của các vùng TRÒN từ UserDefaults (xem regionStateKey).
    private func loadRegionState() {
        let raw = defaults.dictionary(forKey: BgGeo.regionStateKey) ?? [:]
        regionState = raw.compactMapValues { v -> Bool? in
            if let b = v as? Bool { return b }
            if let n = v as? NSNumber { return n.boolValue }
            return nil
        }
    }

    private func saveRegionState() {
        defaults.set(regionState, forKey: BgGeo.regionStateKey)
    }

    /// Áp `maxDaysToPersist` / `maxRecordsToPersist` (giống Android).
    private func applyRetention() {
        let days = (config["maxDaysToPersist"] as? NSNumber)?.intValue ?? -1
        let records = (config["maxRecordsToPersist"] as? NSNumber)?.intValue ?? -1
        guard days > 0 || records > 0 else { return }
        db.trim(maxDays: days, maxRecords: records)
    }

    /// Bỏ trạng thái của những vùng đã bị xoá (tránh UserDefaults phình ra theo thời gian).
    private func pruneRegionState() {
        let ids = Set(BgGeoGeofences.shared.all().compactMap { $0["identifier"] as? String })
        let kept = regionState.filter { ids.contains($0.key) }
        if kept.count != regionState.count {
            regionState = kept
            saveRegionState()
        }
        // Vùng ĐA GIÁC: PHẢI dọn cả `rnbggeo_poly_inside`. Nếu xoá vùng lúc đang ở trong mà
        // để lại {id: true} thì isInsideAnyFence() mãi trả true → pauseInsideGeofence chặn
        // ghi điểm vĩnh viễn (kể cả sau khi khởi động lại app vì khoá này nằm trong prefs).
        let raw = defaults.dictionary(forKey: "rnbggeo_poly_inside") ?? [:]
        let keptPoly = raw.filter { ids.contains($0.key) }
        if keptPoly.count != raw.count {
            defaults.set(keptPoly, forKey: "rnbggeo_poly_inside")
        }
    }

    /// Gửi sự kiện geofence lên `<url>/geofences` (giống Android).
    private func postGeofenceEvent(_ payload: [String: Any]) {
        let base = ((config["url"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
        guard !base.isEmpty, let url = URL(string: base.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/geofences") else { return }
        var headers: [String: String] = ["Content-Type": "application/json"]
        if let h = config["headers"] as? [String: String] { h.forEach { headers[$0.key] = $0.value } }
        // Thiếu 3 khoá này thì server không gán sự kiện cho thiết bị nào → viewer chỉ hiện
        // đám chấm chồng nhau, không lọc được theo máy (57 sự kiện cũ đều "unknown").
        var body = payload
        body["device_id"] = BgGeo.stableDeviceId()
        body["device_platform"] = "ios"
        body["device_name"] = BgGeo.deviceDisplayName
        // Giống Android: bọc theo `httpRootProperty` (mặc định "location").
        let root = (config["httpRootProperty"] as? String) ?? "location"
        let key = (root.isEmpty || root == ".") ? "geofence" : root
        uploader.postEvent(url: url, headers: headers, json: [key: body])
    }

    // MARK: - Log

    private var logURL: URL {
        let dir = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Logs", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("rnbggeo.log")
    }

    @objc func getLogPath(_ resolve: @escaping RCTPromiseResolveBlock,
                          rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(logURL.path)
    }

    @objc func getLogSize(_ resolve: @escaping RCTPromiseResolveBlock,
                          rejecter reject: @escaping RCTPromiseRejectBlock) {
        let size = (try? FileManager.default.attributesOfItem(atPath: logURL.path)[.size]) as? NSNumber
        resolve(size?.intValue ?? 0)
    }

    @objc func getLog(_ maxBytes: Double,
                      resolver resolve: @escaping RCTPromiseResolveBlock,
                      rejecter reject: @escaping RCTPromiseRejectBlock) {
        let text = (try? String(contentsOf: logURL, encoding: .utf8)) ?? ""
        let limit = Int(maxBytes)
        if limit > 0 && text.count > limit {
            resolve(String(text.suffix(limit)))
        } else {
            resolve(text)
        }
    }

    @objc func clearLog(_ resolve: @escaping RCTPromiseResolveBlock,
                        rejecter reject: @escaping RCTPromiseRejectBlock) {
        try? "".write(to: logURL, atomically: true, encoding: .utf8)
        resolve(true)
    }

    @objc func destroyLog(_ resolve: @escaping RCTPromiseResolveBlock,
                          rejecter reject: @escaping RCTPromiseRejectBlock) {
        try? FileManager.default.removeItem(at: logURL)
        resolve(true)
    }

    /**
     Đẩy file log chẩn đoán lên server: `POST <url>/logs`.

     VÌ SAO CẦN: log của lib nằm trong `Library/Logs/rnbggeo.log` của app. Muốn đọc phải
     mở Xcode / cắm cáp — không làm được khi máy ở xa. Đây đúng là rào cản khi chẩn đoán
     các đoạn hành trình bị mất trên iPhone (21/10, 22/10).

     Trả về chuỗi JSON `{ok, status, response, bytes, logPath}` (giống Android).
     */
    @objc func uploadLog(_ maxBytes: Double,
                         resolver resolve: @escaping RCTPromiseResolveBlock,
                         rejecter reject: @escaping RCTPromiseRejectBlock) {
        var base = ((config["url"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
        while base.hasSuffix("/") { base.removeLast() }
        guard !base.isEmpty, let url = URL(string: base + "/logs") else {
            resolve(jsonString(["ok": false, "status": 0,
                                "response": "chưa cấu hình url"]))
            return
        }
        let limit = Int(maxBytes)
        let full = (try? String(contentsOf: logURL, encoding: .utf8)) ?? ""
        let text = (limit > 0 && full.count > limit) ? String(full.suffix(limit)) : full
        guard !text.isEmpty else {
            resolve(jsonString(["ok": false, "status": 0,
                                "response": "log rỗng — kiểm tra logToFile"]))
            return
        }
        var headers: [String: String] = ["Content-Type": "application/json"]
        if let h = config["headers"] as? [String: String] {
            h.forEach { headers[$0.key] = $0.value }
        }
        let payload: [String: Any] = [
            "device_id": BgGeo.stableDeviceId(),
            "device_name": BgGeo.deviceDisplayName,
            "device_platform": "ios",
            "filename": "rnbggeo-" + BgGeo.stableDeviceId() + ".log",
            "bytes": text.count,
            "content": text,
        ]
        // Bọc theo `httpRootProperty` giống sự kiện geofence/diag để backend chỉ cần một
        // cách đọc body (server cũng nhận được dict trần).
        let root = (config["httpRootProperty"] as? String) ?? "location"
        let key = (root.isEmpty || root == ".") ? "log" : root
        let logPath = logURL.path
        diag("api", "uploadLog() — đẩy nhật ký lên server (\(text.count) ký tự)")
        uploader.postEventWithResult(url: url, headers: headers, json: [key: payload]) {
            ok, status, response in
            let out: [String: Any] = [
                "ok": ok,
                "status": status,
                "response": response,
                "bytes": text.count,
                "logPath": logPath,
            ]
            // `encodeJson` là static → closure không cần giữ `self` (URLSession trả lời có
            // thể sau khi module đã bị giải phóng).
            let encoded = BgGeo.encodeJson(out)
            DispatchQueue.main.async { resolve(encoded) }
        }
    }

    @objc func setLogLevel(_ level: Double,
                           resolver resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
        config["logLevel"] = Int(level)
        saveConfig()
        resolve(true)
    }

    @objc func playSound(_ kind: String,
                         resolver resolve: @escaping RCTPromiseResolveBlock,
                         rejecter reject: @escaping RCTPromiseRejectBlock) {
        AudioServicesPlaySystemSound(kind == "notification" ? 1007 : 1057)
        resolve(true)
    }

    // MARK: - Lịch & watchPosition

    @objc func getSchedule(_ resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
        let s = config["schedule"] as? [String] ?? []
        resolve(jsonString(["schedule": s, "enabled": !s.isEmpty, "active": enabled]))
    }

    @objc func startSchedule(_ resolve: @escaping RCTPromiseResolveBlock,
                             rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(jsonString(["schedule": config["schedule"] ?? [], "enabled": true,
                            "active": enabled]))
    }

    @objc func stopSchedule(_ resolve: @escaping RCTPromiseResolveBlock,
                            rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(jsonString(["schedule": config["schedule"] ?? [], "enabled": false,
                            "active": enabled]))
    }

    @objc func startWatchPosition(_ options: NSDictionary,
                                  resolver resolve: @escaping RCTPromiseResolveBlock,
                                  rejecter reject: @escaping RCTPromiseRejectBlock) {
        watchCount += 1
        if !enabled {
            enabled = true
            isMoving = true
            startUpdating()
            startMotion()
            startHeartbeat()
        }
        resolve(true)
    }

    @objc func stopWatchPosition(_ resolve: @escaping RCTPromiseResolveBlock,
                                 rejecter reject: @escaping RCTPromiseRejectBlock) {
        if watchCount > 0 { watchCount -= 1 }
        resolve(true)
    }
}
