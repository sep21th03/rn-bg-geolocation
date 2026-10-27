# Chuyển `rn-bg-geolocation` sang iOS

Tài liệu bàn giao để viết implementation native iOS cho cùng lớp JS.
Cập nhật: **20/10/2026**. Trạng thái iOS hiện tại: **chưa có code native**.

---

## 0. Nguyên tắc

Lớp JS (`index.js` + `types.d.ts`) đã **trung lập nền tảng**: mọi hàm gọi `NativeModules.BgGeo`.
Nếu thiếu module, JS chỉ `console.warn` chứ không throw, và từng API reject có kiểm soát.

Việc cần làm: tạo `ios/` với một `RCTNativeModule` tên **`BgGeo`**, expose đúng các method dưới đây,
bắn đúng tên event. **Không sửa `index.js`** trừ khi bổ sung API mới.

---

## 1. Bản đồ công nghệ

| Chức năng | Android (đã có) | iOS (cần làm) |
|---|---|---|
| Vị trí | FusedLocationProviderClient | `CLLocationManager` + `startUpdatingLocation` |
| Nền | ForegroundService + START_STICKY | `UIBackgroundModes: [location, fetch]` + `allowsBackgroundLocationUpdates` |
| Đứng yên ↔ di chuyển | Activity Recognition + Significant Motion | `CMMotionActivityManager` + `CLLocationManager.startMonitoringSignificantLocationChanges` |
| Geofence tròn | GeofencingClient (tối đa ~90 vùng) | `CLCircularRegion` (**tối đa 20 region/app**) |
| Geofence đa giác | Tự tính point-in-polygon | Giữ nguyên: tự tính, chỉ dùng region bao để đánh thức |
| Lưu trữ | SQLite (`LocationDb`) | SQLite/CoreData — cùng schema JSON |
| HTTP | OkHttp + hàng đợi | URLSession background configuration |
| Log file | thư mục ngoài app | `Documents/` hoặc `Library/Logs/` |
| Notification | Foreground service notification | Local notification (không bắt buộc) |
| Schedule | Handler 60 s | `BGTaskScheduler` hoặc timer khi app sống |

---

## 2. Hợp đồng native bắt buộc (method → Promise)

Nhóm vòng đời:
```
ready(config) -> State JSON string
start() / stop() -> State
getState() / getConfig() -> JSON string
setConfig(partial) -> State
reset(config) -> State
changePace(bool) -> State
getCurrentPosition(opts) -> Location JSON
```

Nhóm dữ liệu:
```
getLocations() -> JSON array
getCount() -> number
sync() -> bool
destroyLocations() / destroyLocation(uuid)
insertLocation(loc) -> uuid
getOdometer() / setOdometer(v) / resetOdometer()
```

Nhóm geofence:
```
addGeofence(g) / addGeofences(list) / removeGeofence(id) / removeGeofences(ids|null)
getGeofences() / getGeofence(id) / geofenceExists(id)
getGeofenceStats()
```

Nhóm thiết bị/quyền:
```
hasPermission() -> {fine, background}
requestPermission() -> ProviderChangeEvent     (dùng CLLocationManager.requestAlwaysAuthorization)
getProviderState() -> ProviderChangeEvent
getDeviceInfo() / getSensors() -> JSON
isPowerSaveMode() / getConnectivityInfo()
```

Nhóm lịch & log:
```
getSchedule() / startSchedule() / stopSchedule()
getLogPath() / getLogSize() / getLog(maxBytes) / clearLog() / destroyLog()
setLogLevel(n) / playSound(kind)
startBackgroundTask() / endBackgroundTask(id)
startWatchPosition(opts) / stopWatchPosition()
```

> Mọi method trả JSON **dạng chuỗi** (Android đang làm vậy) để lớp JS parse bằng `parseMaybeJson`.

---

## 3. Hợp đồng event (tên phải khớp tuyệt đối)

| Tên | Payload |
|---|---|
| `rnbggeo:location` | Location |
| `rnbggeo:motionchange` | `{isMoving, activity, location}` |
| `rnbggeo:activitychange` | `{activity, confidence, isMoving, timestamp}` |
| `rnbggeo:geofence` | `{identifier, action, location, timestamp}` |
| `rnbggeo:geofenceschange` | danh sách vùng đang giám sát |
| `rnbggeo:http` | `{success, status, response}` |
| `rnbggeo:enabledchange` | `{enabled}` |
| `rnbggeo:connectivity` | `{connected, type}` |
| `rnbggeo:powersavechange` | `{isPowerSaveMode}` |
| `rnbggeo:locationfilter` | `{reason, distance, coords, timestamp}` |
| `rnbggeo:heartbeat` | `{location, battery, odometer, timestamp}` |
| `rnbggeo:providerchange` | `{enabled, gps, network, status, accuracyAuthorization}` |
| `rnbggeo:authorization` | như trên |
| `rnbggeo:schedule` | `{active}` |

Android gửi payload trong `{ payload: "<json>" }` — iOS phải gửi **cùng dạng** để JS không phải sửa.


---

## 4. Ánh xạ cấu hình

| Config | Android | iOS |
|---|---|---|
| `distanceFilter`, `interval`, `desiredAccuracy` | LocationRequest | `CLLocationManager.distanceFilter`, `desiredAccuracy` |
| `stationaryRadius`, `stopTimeout` | StopDetector + AR | `CLLocationManager` + `CMMotionActivityManager`; **iOS cần ~200 m để phát hiện rời chỗ** (giới hạn OS) |
| `activity.*` (`motionTriggerDelay`, `minimumActivityRecognitionConfidence`, `triggerActivities`) | Activity Recognition | `CMMotionActivityManager` (`confidence`, `startDate`) — `motionTriggerDelay` áp dụng tương tự |
| `pauseInsideGeofence`, `zeroLossMode` | tự viết | port nguyên logic |
| `geofenceProximityRadius` | lọc vùng gần | **quan trọng hơn**: iOS giới hạn 20 region → phải lọc theo khoảng cách |
| `useSignificantChangesOnly` | xấp xỉ | dùng thật `startMonitoringSignificantLocationChanges` |
| `maxDaysToPersist`, `maxRecordsToPersist` | prune SQLite | prune cùng công thức |
| `schedule` | tick 60 s | `BGAppRefreshTask` + kiểm tra khi app foreground |
| `heartbeatInterval` | Handler | `Timer` + `BGProcessingTask` (nếu cần nền) |

Cấu trúc config vẫn **phẳng** như Android (45+ khoá) — không dùng compound để giữ tương thích.

---

## 5. Khác biệt nền tảng phải chấp nhận

1. **Giới hạn 20 geofence** (iOS) so với ~90 (Android). Cần `geofenceProximityRadius` + sắp theo khoảng cách.
2. **Không có Activity Recognition nền như Android**: `CMMotionActivityManager` chỉ chạy khi app còn sống;
   khi app bị kill, chỉ còn stationary geofence của OS → chấp nhận mất ~200 m đầu chuyến
   (giống transistorsoft).
3. **Quyền**: iOS có "When In Use" / "Always"; `getProviderState().status` phải map:
   `0` = denied, `2` = whenInUse, `3` = always.
4. **`accuracyAuthorization`**: iOS 14+ có "Precise: Off" → trả `0`, còn bình thường `1`.
5. **Không có foreground service notification** — dùng local notification tuỳ chọn.
6. **Background HTTP**: dùng `URLSessionConfiguration.background` để gửi bù khi app ngủ.

---

## 6. Checklist triển khai iOS

- [ ] Tạo `ios/BgGeo.swift` + `BgGeo.m` (bridge) + `rn_bg_geolocation.podspec`
- [ ] `Info.plist`: `NSLocationAlwaysAndWhenInUseUsageDescription`, `NSLocationWhenInUseUsageDescription`,
      `NSMotionUsageDescription`, `UIBackgroundModes: location, fetch`
- [ ] `CLLocationManager` + delegate: `didUpdateLocations`, `didExitRegion`, `didEnterRegion`,
      `didChangeAuthorization`, `locationManagerDidPauseLocationUpdates`
- [ ] Chuyển toàn bộ logic lọc/Kalman/odometer sang Swift **hoặc** gọi lại thuật toán đã port
      (giữ nguyên ngưỡng: `trackingAccuracyThreshold`, `maxImpliedSpeed`, `stationaryDistanceThreshold`)
- [ ] SQLite cùng schema (xem `LocationDb.kt`)
- [ ] Hàng đợi HTTP + `URLSession` background, cùng format JSON
- [ ] Bắn đủ 14 event với payload `{payload: "<json>"}`
- [ ] `getProviderState` map đúng `status`/`accuracyAuthorization`
- [ ] Test trên máy thật: rời nhà, vào công ty, tắt/mở GPS, kill app, reboot

---

## 7. Cách kiểm thử song song hai nền tảng

1. Dùng cùng một app lab (`bgGeoLab`) — hiện `App.tsx` đã gọi `getConfig`, `getProviderState`,
   `getDeviceInfo`, `getSchedule`, `insertLocation`, `destroyLocation` lúc khởi động.
2. So sánh `State` trả về giữa hai nền tảng (cùng khoá, cùng kiểu).
3. So sánh log: Android có `Diag` ghi file; iOS cần API tương đương để `getLog` hoạt động.
4. Kiểm tra 4 kịch bản: đứng yên 30 phút → đi xe 5 km → vào geofence → mất mạng 10 phút.
