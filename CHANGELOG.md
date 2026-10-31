# Changelog

## 0.1.12 — iOS: GIÀNH UNUserNotificationCenter delegate + MẶC ĐỊNH TẮT thông báo native

### 0.1.12a — ĐỔI MẶC ĐỊNH: `geofenceNotifications` = **false**

**Quyết định (31/10/2026):** lib chỉ lo **lấy vị trí chính xác + bắn sự kiện ENTER/EXIT + ghi hành
trình**. Việc hiện thông báo là **của APP** — vì nội dung thông báo, navigation/deep-link khi bấm, và
nghiệp vụ ("đã chấm công chưa", giờ ca, nhắc trước/sau ca…) đều là chuyện của app; lib generic không
biết và không nên biết.

Đổi ở cả 3 tầng (mặc định phải khớp nhau, nếu không thì nền tảng này tắt còn nền tảng kia bật):

| Tầng | Trước | Sau |
|---|---|---|
| `index.js` `defaultConfig()` | `true` | **`false`** |
| `ios/BgGeo.swift` `defaultConfig()` | `true` | **`false`** |
| `ios/BgGeo.swift` guard trong `setupNotificationCenter()` / `notifyGeofence()` | `?? true` | **`?? false`** |
| `android/GeofenceNotifier.kt` | `optBoolean(…, true)` | **`optBoolean(…, false)`** |

Hệ quả tích cực: mặc định lib **không đụng tới `UNUserNotificationCenter.delegate`** ⇒ không còn tranh
delegate với Notifee, và cũng **không tự xin quyền thông báo** (app tự lo qua Notifee).

**Kèm sửa một lỗ hổng liên quan:** `ready()`, `setConfig()`, `reset()` trước đây **không** gọi
`setupNotificationCenter()`. Vì `init()` chạy TRƯỚC `ready()`, app bật `geofenceNotifications: true`
qua `ready()` sẽ **không bao giờ giành được delegate** ⇒ thông báo đặt lúc app đang MỞ bị iOS nuốt
(đúng lỗi 0.1.12b mô tả ở dưới, nhưng quay lại âm thầm). Nay cả 3 hàm đều gọi lại
`setupNotificationCenter()` (idempotent: đã giữ delegate thì không làm gì).

**Và: TẮT thì TRẢ LẠI delegate.** Trước đây khi `geofenceNotifications` chuyển `true → false` (hoặc khi
config ĐÃ LƯU của bản cũ là `true` mà config mới là `false`), lib chỉ dừng phát thông báo nhưng **vẫn giữ
`UNUserNotificationCenter.delegate` suốt phiên** — Notifee/thư viện khác phải đi vòng qua lib một cách vô
ích. Đo thật trên iPhone 11 Pro 31/10: log `09:15:18.115 … nhận delegate` (init, config lưu cũ = true)
rồi `09:15:18.265 … TẮT`. Nay khi tắt, lib **gán trả `previousNotificationDelegate`** và log
`thông báo: TẮT … TRẢ UNUserNotificationCenter delegate về <tên lớp cũ>`. Log cũng chỉ ghi khi trạng thái
ĐỔI (`notificationStateApplied`) nên không lặp ở init/ready/setConfig/reset.

⚠️ **Ai đang dùng lib:** `bgGeoLab` không set `geofenceNotifications` ⇒ **thông báo của lab sẽ tắt
theo**. Muốn test thông báo native thì bật tường minh `geofenceNotifications: true`.

### 0.1.12c — ĐỆM sự kiện VÙNG khi JS chưa đăng ký listener (lỗi "app không nhận được sự kiện")

**Lỗi (đo thật 31/10/2026, app chấm công, iPhone 11 Pro):** EXIT Công ty lúc **11:38** — log native có
`khôi phục theo dõi lúc khởi động (iOS đánh thức ở nền)` (tức JS ĐÃ chạy) và có `diag geofence: EXIT Công
ty` — nhưng **app không hề nhận được sự kiện** nên không phát thông báo "chưa chấm ra ca". Server vì vậy
**thiếu mốc RA CA ca sáng**.

**Gốc:** `emit()` có `guard hasListeners else { return }` — **bỏ luôn sự kiện**, không đệm. App thường
gọi `await BgGeo.ready(...)` TRƯỚC khi đăng ký `onGeofence` (để lấy config về trước), mà `ready()` lại
`syncRegions()` ⇒ `didDetermineState`/`didExitRegion` bắn sự kiện **ngay trong lúc đó**, khi React chưa
mount tới chỗ đăng ký listener ⇒ `hasListeners` còn `false` ⇒ sự kiện mất.

**Nay:** sự kiện `rnbggeo:geofence` bắn lúc chưa có listener được **ĐỆM** lại và **TRẢ về JS ở
`startObserving()`**. Chỉ đệm sự kiện VÙNG (đệm `location` sẽ làm JS nhận một loạt điểm cũ vô ích).

- Config `eventBufferSeconds` (**mặc định 600**; `0` = tắt, quay lại hành vi cũ). Sự kiện cũ hơn ngưỡng
  bị BỎ khi trả lại — ENTER/EXIT từ vài tiếng trước không còn giá trị hành động.
- Trần cứng 20 sự kiện (đệm chỉ để bắc cầu vài giây lúc JS khởi động, không phải hàng đợi).
- Log khi trả: `đệm sự kiện vùng: trả N sự kiện cho JS (chúng bắn lúc JS chưa đăng ký listener)`.

**Kiểm chứng trên máy (31/10, log 14:01:50):** buộc app chuẩn hoá lại vùng lúc khởi động ⇒

```
14:01:50.388 diag geofence: ENTER Công ty (đang ở trong vùng lúc đăng ký)     ← bắn lúc JS chưa nghe
14:01:50.618 đệm sự kiện vùng: trả 1 sự kiện cho JS (chúng bắn lúc JS chưa đăng ký listener)
```

⇒ 230 ms sau, sự kiện được trả về JS. Trước bản vá, sự kiện này **mất hẳn**.

### 0.1.12b — iOS: thông báo geofence hiện cả khi app đang mở

**Lỗi (đo thật 31/10/2026, app chấm công + Notifee):** iOS "không có thông báo khi vào geo" —
thông báo ENTER/EXIT đặt lúc app **đang mở** thì mất hút, còn đặt lúc app ở **nền** thì hiện bình thường.

**Gốc:** thông báo tạo khi app đang mở chỉ được hiện nếu `UNUserNotificationCenter.delegate` gọi
`completionHandler` trong `willPresent`. Bản cũ chỉ nhận delegate khi chỗ đó còn TRỐNG
(`if center.delegate == nil { center.delegate = self }`), nhưng **Notifee đã giành delegate ngay lúc
khởi động**: nó `observe` trong `UIApplicationDidFinishLaunchingNotification` — TRƯỚC khi JS chạy và
trước `init()` của module. Lúc đó `_originalDelegate` của Notifee = nil, nên `willPresent` của Notifee
với thông báo **không phải của nó** rơi vào nhánh `else if (_originalDelegate != nil && …)` ⇒ **không
gọi completionHandler** ⇒ iOS bỏ thông báo (Apple: *"if the handler is not called in a timely manner
then the notification will not be presented"*).

**Nay:**

- `setupNotificationCenter()` **nhận** delegate kể cả khi đã có chủ, ghi nhớ chủ cũ vào
  `previousNotificationDelegate`, và log 1 dòng diag: `thông báo: nhận UNUserNotificationCenter
  delegate (chủ cũ: <tên lớp>)` ⇒ đọc log là biết ai đang giữ delegate.
- `willPresent`: thông báo **của lib** (`rnbggeo-fence-…`) → tự hiện `[.banner, .list, .sound]`;
  mọi thông báo khác → **chuyển tiếp nguyên** cho chủ cũ (Notifee tự quyết theo
  `ios.foregroundPresentationOptions`); không ai nhận thì `completionHandler([])` (không để iOS chờ).
- Thêm `didReceive response:` và `openSettingsFor notification:`. Thông báo **của lib** thì xử lý
  ngay tại chỗ (`completionHandler()` liền + 1 dòng log), **không** đẩy sang chủ cũ: đẩy sang thì
  Notifee coi là thông báo "lạ", phát một sự kiện PRESS không ai nghe rồi mới gọi `completionHandler`
  sau **15 giây** (`dispatch_after(…, 15 * NSEC_PER_SEC, …)` trong `NotifeeCore+UNUserNotificationCenter.m`).
  Mọi thông báo **khác** (của Notifee) vẫn **chuyển tiếp nguyên** cho chủ cũ ⇒ app không mất
  `onForegroundEvent`/`onBackgroundEvent`.

**Kiểm chứng (log máy iPhone 11 Pro):** trước khi sửa 23/27 thông báo hiện (đặt lúc ở nền), 4/4 thông
báo đặt lúc app đang mở bị bỏ. Sau khi sửa: log có dòng "nhận UNUserNotificationCenter delegate
(chủ cũ: NotifeeCoreUNUserNotificationCenter)".

## 0.1.11 — Thông báo geofence do APP tự đặt nội dung (lib không fix cứng nghiệp vụ)

**Yêu cầu:** lib dùng cho nhiều app ⇒ **không fix cứng giá trị nào**; app phải tự đặt chữ và tự quyết
định nghiệp vụ (chấm công là của app, lib chỉ định vị + bắn sự kiện).

**Nay:**

- Thông báo ENTER/EXIT/DWELL lấy nội dung từ `extras` của TỪNG VÙNG nếu app có đặt:
  `extras.entryTitle`, `extras.exitTitle`, `extras.dwellTitle` (tiêu đề) và `extras.note` (dòng thêm).
  Không đặt ⇒ giữ nguyên mặc định "Đã đến / Đã rời <tên vùng>" ⇒ **app đang dùng lib không bị ảnh hưởng**.
- Log thêm kết quả HTTP vào file log (`http result: success=… status=… resp=…`) — cần cho việc chẩn đoán
  khi app ở nền (event không tới JS thì chỉ log file mới cho biết lý do).
- Không thêm hằng số/nghiệp vụ nào của app vào lib: toạ độ, URL server, bán kính, giờ ca, chữ thông báo
  đều do app truyền vào (config/geofence).


## 0.1.10 — Thông báo Android ĐỨNG YÊN (hết nhấp nháy) + ghi chú chống báo sớm vùng nhỏ

**Yêu cầu (28/10):** thông báo trên Android bị "spam" — nội dung nhấp nháy liên tục trong khay.

**Nay:**

- Thông báo thường trực của service **không còn kèm "N điểm chờ gửi"**. Trước đây text là
  `"<trạng thái> • N điểm chờ gửi"` nên mỗi lần hàng đợi đổi (mất mạng là đổi liên tục) là
  `NotificationManager.notify` được gọi lại ⇒ thấy nhấp nháy. Text nay cố định nên
  `updateNotificationText()` tự bỏ qua ⇒ thông báo đứng yên. Muốn xem toạ độ sống thì đặt
  `notificationLiveCoords: true` (mặc định tắt, và vẫn có giới hạn nhịp cập nhật).
- Ghi chú trong `GeofenceReceiver.toFence()`: bán kính vùng đăng ký với Geofencing API của Android
  có thể bị hệ điều hành nới lên **tối thiểu 100 m** ⇒ với vùng nhỏ hơn, ENTER của OS có thể bắn khi
  người dùng còn NGOÀI bán kính thật. iOS đã tự chấm lại bán kính thật trước khi báo (xem
  `BgGeo.swift`), **Android thì chưa** — nếu cần vùng nhỏ (<100 m) phải làm nốt phần này.

## 0.1.9 — `DeviceInfo.name` khớp `device_name` (cả 2 nền tảng) + chống chết app khi JS truyền sai

**Yêu cầu:** app cần biết **tên máy** để đóng dấu vào dữ liệu riêng (bảng chấm công) sao cho trùng
đúng tên lib gửi lên server (`device_name`); và lib **không được làm chết app** khi JS truyền sai.

**Nay:**

- `getDeviceInfo()` trả thêm `name` = **đúng giá trị đang gửi làm `device_name`**:
  Android `Build.MODEL` (vd `SM-A066B`), iOS `UIDevice.model` (vd `iPhone`, giả lập
  `iPhone (Simulator)`). Trước đây Android **không có** `name` (⇒ app phải tự đoán), còn iOS trả
  `UIDevice.name` — tên người dùng đặt (vd "iPhone của Hoa") ⇒ **cùng một máy mà server thấy 2 tên**.
- `addGeofences(list)` (Android): phần tử không phải object (`null` / `undefined` / số) nay
  `promise.reject('E_GEOFENCE', 'vùng #i không phải object (nhận …)')` thay vì ném
  NullPointerException **làm chết app**. (iOS vốn an toàn vì `as? [String: Any]` chỉ trả nil.)

**Lưu ý cho người bảo trì:** dictionary literal trong Swift **không được trùng khoá** — trùng thì
nổ `Fatal error: Dictionary literal contains duplicate keys` (SIGTRAP) **lúc chạy**, dù build vẫn
xanh và `swiftc -parse` sạch. Sửa `getDeviceInfo()`/`getSensors()` xong phải chạy thật, không tin
build. (Kotlin/`JSONObject.put` thì trùng khoá chỉ ghi đè, không trap.)

## 0.1.8 — Chế độ CHẤM CÔNG: tắt "viền xanh", đứng yên tắt hẳn GPS (pin trước, như lib gốc)

**Yêu cầu:** app chỉ cần **chấm công vùng** (ENTER/EXIT geofence), **không** cần vẽ hành trình liền
mạch; không chấp nhận "viền xanh" hiện thường trực; đứng yên thì không được giữ location (tốn pin).

**Nay:**

- `backgroundActivitySession` **mặc định `false`** (đảo lại mặc định của 0.1.7) ⇒ **không có "viền
  xanh"**, app ngủ khi không cần. Đặt `true` khi cần vẽ hành trình liền mạch (đo thật 26/10:
  682 điểm / 9,18 km / 22 phút, odometer khớp đường vẽ 99,8 %) — đổi lại viền xanh hiện thường trực.
- `stationaryLowPowerGps: false` (mặc định): đứng yên quá `stopTimeout` (5 phút) thì **tắt hẳn GPS**
  ⇒ mũi tên vị trí biến mất, app ngủ. Việc đánh thức khi máy rời chỗ do **geofence "vị trí cuối"
  (150 m) + significant-location + Background Fetch** — tất cả ở tầng hệ điều hành.
- bgGeoLab: thêm preset **"Chấm công (pin thấp)"** ở tab Pin (1 nút áp cả bộ cấu hình).

**Vì sao chấm công KHÔNG cần session** (quan trọng, tránh hiểu sai):

- ENTER/EXIT vùng do lib tự chấm trên **từng mẫu GPS** (point-in-circle / point-in-polygon); native
  region chỉ là "chuông đánh thức". Khi người dùng **di chuyển**, app đã thức (do vùng "vị trí cuối"
  đánh thức) ⇒ **mốc VÀO vùng chính xác ~1–2 giây**.
- **Mốc RỜI vùng trễ ~200 m** (≈30 s đi xe, 2–3 phút đi bộ): iOS chỉ đánh thức app sau ~200 m di
  chuyển — **giới hạn của hệ điều hành**, không sửa được ở tầng app. Đây là giá phải trả để đứng yên
  không tốn pin. Nếu cần mốc rời vùng sát hơn: đặt `stationaryLowPowerGps: true` (giữ GPS thô 100 m
  khi đứng yên ⇒ phát hiện trong ~100 m) — đổi lại mũi tên vị trí hiện liên tục.
- Lưu ý: `geofenceModeHighAccuracy` của lib gốc **là Android-only** ("Android only. Enables
  high-accuracy mode for geofence-only…"), nên trên iOS không có thêm cơ chế nào để chấm vùng sát hơn.

**Trạng thái session vẫn xem được trên máy:** `getState().bgActivitySession` + công tắc ở tab Pin.

**Đã publish lên npm:** `rn-bg-geolocation@0.1.8` (public, tag `latest`, maintainer `sep21th03`).
App lab đã chuyển từ tarball nội bộ sang bản npm: `"rn-bg-geolocation": "^0.1.8"`.
Kiểm chứng: `npm pack --dry-run` = 38 file/128 kB (có `types.d.ts`, `podspec`, `android/src/main`, `ios/`);
build Android `BUILD SUCCESSFUL`; iOS `pod install` → `Podfile.lock: rn-bg-geolocation (0.1.8)`;
`xcodebuild Release` + cài lên iPhone 11 Pro OK.

## 0.1.7 — `CLBackgroundActivitySession` BẬT mặc định (mode "hành trình liền mạch")

**Lý do đổi mặc định:** đo thật 24–26/10/2026 (iPhone 11 Pro, đọc từ log máy) — khi session TẮT,
app được iOS đánh thức **vào nền** (do geofence) chỉ chạy được **~2 phút** rồi bị treo, lặp lại ở
mỗi lần đánh thức. Ca rõ nhất 26/10 sáng: chuyến đi 9 phút chỉ ghi được ~2,5 phút, giữa chuyến mất
**1.554 m** (bản đồ vẽ một đường thẳng). Đúng như tài liệu Apple: hệ thống chỉ cấu hình *"giữ app
chạy để nhận location liên tục"* khi `startUpdatingLocation()` được gọi lúc app **ĐANG Ở FOREGROUND**
— app được OS đánh thức vào nền không bao giờ thoả điều kiện đó (dù `allowsBg=1`, `gpsRunning=1`).

**Nay:** `backgroundActivitySession` **mặc định `true`** (đảo lại mặc định cũ). Đây là API Apple
khuyến nghị: *"ask the system to not suspend your app while location services are active"*.

- Muốn **tắt** (chỉ còn mũi tên vị trí, không "viên xanh"): `backgroundActivitySession: false`.
- Bật session ⇒ app **không bị treo** khi đang dùng location ⇒ hành trình liền mạch.
  Đổi lại: status bar **LUÔN hiện "viên xanh"** (Apple mô tả session là *"manages a visual indicator
  that keeps your app in use in the background"*) — không tắt được.
- Đã áp dụng ở cả 3 lớp: `defaultConfig()` (Swift), `defaultConfig` (index.js), và mọi chỗ đọc
  config dùng `?? true` thay `?? false` (`ensureBackgroundSession`, `applyLocationSettings`)
  ⇒ thiếu khoá trong config cũng vẫn BẬT.

**Xem trạng thái ngay trên máy (mới):**

- `getState()` trả thêm 2 trường: `backgroundActivitySession` (giá trị cấu hình) và
  `bgActivitySession` (**trạng thái THẬT** — session có đang sống hay không).
- Tab **Pin** của bgGeoLab có thẻ *"Session chạy nền (CLBackgroundActivitySession)"*: công tắc
  bật/tắt ngay lúc chạy + 2 chip trạng thái. Đổi công tắc → `setConfig()` → lib tạo/huỷ session
  ngay, **không cần build lại** (tiện A/B cùng lộ trình).
- Log/diag vẫn ghi rõ: `background activity session: BẬT (giữ app chạy nền liên tục)` /
  `TẮT (đứng yên / dừng tracking)`, và dòng `app VÀO NỀN … session=1`.

**Lưu ý phối hợp với `stationaryLowPowerGps`:** lab đang đặt `stationaryLowPowerGps: true`
(đứng yên vẫn giữ location thô 100 m) ⇒ location **không bao giờ tắt** ⇒ session luôn sống ⇒
**"viên xanh" hiện cả khi đứng yên** và pin hao hơn. Muốn viên xanh tắt khi đứng yên thì đặt
`stationaryLowPowerGps: false` (đổi lại: phát hiện rời chỗ chậm hơn — xem mục 13.6/13.7 handoff).

**Cách kiểm chứng:** đi 1 chuyến ~10 phút, khoá màn hình, **đừng vuốt app**; sau đó so số điểm với
SM-A066B và tìm dòng `gap` / đoạn "ĐỨT" trên viewer. Kỳ vọng: **không còn dòng `gap` giữa chuyến**
và không còn đoạn ĐỨT — mật độ mẫu đều ~10 m suốt chuyến.

## 0.1.6 — geofence "trước khi kết thúc" (đúng cơ chế lib gốc cho `stopOnTerminate: false`)

**Vá khoảng trống làm mất đoạn đầu chuyến khi app bị kill giữa đường.**

Trước bản này, iOS chỉ đăng ký geofence quanh vị trí cuối ở 2 lúc: khi vào trạng thái đứng yên, và khi
**đang đi** thì dời mỗi `safetyGeofenceIntervalMinutes` phút (mặc định 5). Hở đúng một khoảng: app bị
kill (vuốt khỏi đa nhiệm / OS thu hồi / hết pin) ngay sau khi rời foreground → vùng đang có có thể cũ
tới 5 phút (đi 40 km/h là lệch ~3,3 km) ⇒ OS đánh thức sai chỗ, đoạn đầu chuyến bị mất (đúng ca 23/10:
mất 399 m đầu chuyến vì phải chờ sự kiện vùng).

**Nay (iOS)** — `BgGeo.registerRegionBeforeTermination()` chốt lại vùng quanh vị trí cuối ở 2 thời điểm mới:

- `appDidEnterBackground` — **đây mới là điểm "trước khi bị kill" thực tế trên iOS**, vì
  `applicationWillTerminate` KHÔNG được gọi khi người dùng vuốt app khỏi đa nhiệm (SIGKILL) hay khi OS
  thu hồi một app đang treo;
- `willTerminateNotification` — ca app đang chạy mà bị kết thúc (hết pin, OS thu hồi, khởi động lại máy).

Chi tiết:

- Bọc `beginBackgroundTask` để chắc chắn `startMonitoring` + ghi log chạy xong trước khi iOS treo tiến
  trình (region sống ở tầng OS nên sau đó không cần app chạy).
- Chỉ chạy khi `stopOnTerminate: false` + tracking đang bật + còn quyền vị trí.
- `stopOnTerminate: true` → khi sắp kết thúc thì **XOÁ** vùng vị trí cuối, đúng câu của lib gốc:
  "the SDK calls stop() as the app terminates" — để OS không đánh thức app lên một cách vô ích.
- `updateLastKnownRegion()` fallback sang `CLLocationManager.location` khi chưa có mẫu trong RAM (tiến
  trình vừa được OS đánh thức lại) → thà vùng hơi cũ còn hơn không có vùng nào.
- Diag mới để kiểm chứng từ xa:
  `geofence trước khi kết thúc (vào nền) — vùng 150 m quanh vị trí cuối, OS sẽ relaunch app khi rời khỏi vùng`

**Cấu hình lab (`bgGeoLab/App.tsx`)**: `backgroundActivitySession: false` (theo đúng mặc định lib gốc —
session API không xuất hiện lần nào trong docs AppConfig/GeoConfig của transistorsoft) + `zeroLossMode: false`.
Đánh đổi đã biết: chỉ có mũi tên vị trí, không có "viên xanh"; khi iOS treo app giữa chuyến thì đoạn đầu
chuyến phụ thuộc vào vùng đánh thức (200 m–1 km).

Cách kiểm chứng: mở app → về Home (đừng vuốt) → đi ~300–500 m → xem log máy hoặc `/diag` có dòng
`geofence trước khi kết thúc (vào nền)` và sau đó có dòng `khôi phục theo dõi lúc khởi động (iOS đánh thức ở nền)`.

## 0.1.5 — chặn watchdog "đá oan" khi xe đỗ trong vùng geofence

Đo thật 23/10: SM-A066B (máy khỏe) đỗ xe ở **Nhà** — không có mẫu nào vì đứng yên thì không cần
fix dày, nhưng `isMoving` còn `true` nên watchdog đã **đá oan 3 lần** (gap 165–281 s mà máy chỉ
cách nhau **4–7 m**).

**Nay:** watchdog KHÔNG đá khi đang ở TRONG một vùng geofence (cả Android và iOS):
- đứng yên ở nhà/công ty thì không có mẫu là ĐÚNG;
- khi rời vùng, geofence EXIT của hệ điều hành sẽ đánh thức app (cộng watchdog-trong-vùng 10 phút
  làm dự phòng) → không cần tự đá.

Ca "dừng đèn đỏ giữa đường" (ngoài mọi vùng) vẫn được đá bình thường.

## 0.1.4 — bịt lỗ hổng watchdog: ca "dừng xe ở đèn đỏ rồi mất hành trình"

**Sửa — watchdog "nhà cung cấp vị trí im lặng" bỏ sót đúng ca nguy hiểm nhất**

Bản 0.1.2 chỉ "đá" lại provider khi app cho là **ĐANG DI CHUYỂN** (`isMoving`). Nhưng ca thật
ngày 23/10 trên SM-A127F cho thấy đúng chỗ hổng đó:

```
07:23:04 → 07:24:13   đi 20–49 km/h (đang chạy)
07:24:13 → 07:24:43   chỉ còn 38 m trong 30 giây = 1,3 m/s  ⇒ DỪNG XE (đèn đỏ)
07:24:43 → 07:34:40   IM LẶNG 597 giây ⇒ MẤT 4,8 km hành trình
```

Nếu máy đứng đủ lâu để `isMoving` chuyển về `false` (4 mẫu chậm liên tiếp trong 40 m), thì
watchdog bản cũ **sẽ không đá** — và đoạn hành trình sau đèn đỏ vẫn mất như thường.

**Nay:** ngưỡng đá phụ thuộc trạng thái, nhưng **luôn đá**:
- Đang đi (hoặc Activity Recognition báo `in_vehicle` / `on_bicycle` / `walking` / `on_foot` /
  `running`) → đá sau `providerSilenceMinutes` (mặc định 3 phút).
- Trông như đứng yên → im lâu là chuyện thường nên chờ **gấp 3 lần** (9 phút), nhưng **vẫn đá**,
  vì `tracking == true` nghĩa là app ĐÃ xin nhận vị trí liên tục mà 9 phút không có gì.

Diag `gap` nay ghi rõ nó đá vì "đang di chuyển" hay vì "trông như đứng yên (act=…)" để lần sau
đọc log là biết ngay.

## 0.1.3 — đếm số vệ tinh (phép đo để biết máy có dùng được GNSS hay không)

**Thêm mới — `GnssWatcher` (Android)**

- Đọc số vệ tinh **NHÌN THẤY** và số vệ tinh **ĐANG DÙNG ĐỂ TÍNH VỊ TRÍ**, dựa trên
  `LocationManager.registerGnssStatusCallback` (chỉ ĐỌC phiên GNSS đang chạy, không tự bật GNSS).
- `getState()` trả thêm `gnssUsed`, `gnssVisible`, `gnssText` (chuỗi tiếng Việt sẵn để hiện lên UI).
- Dòng diag `alive` (15 phút/lần) nay kèm `sat=<dùng được>/<nhìn thấy>` → có chuỗi thời gian
  để đối chiếu với lúc mất hành trình.
- Dòng diag `gap` của watchdog nay kèm `sat=…` → biết ngay **lúc im lặng máy có thấy vệ tinh không**.
- App lab: thêm thẻ **"Vệ tinh (GNSS)"** ở tab Tổng quan, cập nhật mỗi 3 giây, có hướng dẫn đọc.
- iOS không có API công khai tương đương ⇒ `gnssUsed`/`gnssVisible` trả `-1`.

**Vì sao cần — đây là phép đo tách "phần cứng" khỏi "phần mềm"**

"Máy không lấy được vị trí" có 2 nguyên nhân khác hẳn nhau về cách xử lý:

| Máy báo | Nghĩa là | Hướng xử lý |
|---|---|---|
| Ngoài trời mà **nhìn thấy 0–5** | Máy KHÔNG nghe được vệ tinh | **Anten/GNSS của máy**, hoặc máy bị che kín |
| **Nhìn thấy 20–50** mà dùng được 0 ngoài trời | Anten tốt, không ra được vị trí | **Phần mềm** (Play Services / hệ điều hành) |

Trước bản này app chỉ biết "không có mẫu vị trí" nên KHÔNG phân biệt được hai ca trên.

**Mốc tham chiếu đo được (SM-A066B, máy khỏe):** trong nhà `0/52`; khi chạy xe ngoài đường
"dùng được" lên 8–20 và sai số tụt xuống ~2 m.

## 0.1.2 — watchdog "nhà cung cấp vị trí im lặng" + phản hồi khi đẩy log

**Thêm mới — `providerSilenceMinutes` (mặc định 3, `0` = tắt)**

- Phát hiện ca "app cho là ĐANG DI CHUYỂN mà nhà cung cấp vị trí không giao mẫu nào" trong
  N phút, rồi tự chữa:
  1. ghi sự kiện diag **`gap`** lên server (`<url>/diag`) — nhìn thấy được từ xa, không cần
     cắm cáp đọc file log;
  2. "đá" lại nhà cung cấp: đăng ký lại request vị trí + gọi `getCurrentLocation`
     (Android) / `startUpdatingLocation()` + `requestLocation()` (iOS) một lần.
- **Chỉ chạy khi app cho là đang di chuyển** (`isMoving`), không chạy khi đứng yên — nếu đứng
  yên thì không có mẫu là ĐÚNG thiết kế (GPS đã tắt theo `stopTimeout`), đá vào chỉ tốn pin.
- Có khoá chống đá dồn dập (5 phút/lần).
- Vì sao cần: đo thật 23/10 trên SM-A127F — request vị trí đang hoạt động, service sống
  (nhịp `alive` 15 phút đều), mạng tốt (`post → 200`) mà FusedLocationProvider trả về
  **0 mẫu trong 10 phút 27 giây** ⇒ mất **4.841 m** hành trình. Máy đó còn bị y hệt 2 lần
  nữa trong ngày 22/10 (9,0 phút và 5,7 phút). Trước bản này KHÔNG có gì phát hiện.
- Log `BẮT ĐẦU theo dõi` nay in thêm `providerSilence=…m` để kiểm được cấu hình từ xa.

**Sửa — nút "ĐẨY LOG LÊN SERVER" không có phản hồi nào khi bấm**

- Đo thật: bấm 6 lần trong 3 giây (không biết đã ăn hay chưa) ⇒ server nhận 6 tệp log.
- Nay có **flash message** ngay dưới nút + **toast** (Android):
  "Đang gửi log lên server…" → "ĐÃ GỬI XONG lúc HH:MM:SS — 683,4 KB (HTTP 200). Xem tại …/logs",
  hoặc dòng đỏ nêu rõ mã HTTP khi thất bại.
- Nhãn nút đổi theo trạng thái: `ĐANG GỬI…` → `VỪA GỬI — chờ Ns` → `ĐẨY LOG LÊN SERVER`.
- **Chống bấm lặp 2 lớp**: lớp `uploading` (chặn trong lúc request đang bay) không đủ vì log
  gửi qua wifi mất ~0,33 giây (đo được: 4 lần bấm ⇒ 4 lần gửi), nên thêm lớp **cooldown 5
  giây** sau khi gửi THÀNH CÔNG. Đã đo lại: 3–4 lần bấm liên tiếp ⇒ đúng 1 lần gửi.

## 0.1.1 — sửa lỗi đa thiết bị + đẩy nhật ký lên server

**Sửa lỗi (đều phát hiện từ dữ liệu thật ngày 22–23/10/2026)**

- **Android — `getDeviceInfo()` thiếu `device_id`.** Hàm chỉ trả `identifier` (ANDROID_ID),
  trong khi lớp JS và iOS dùng khoá `device_id`. Hệ quả thật: app lab đọc được chuỗi rỗng
  nên server gom MỌI định nghĩa geofence của Android vào nhóm `"unknown"` → vùng "Cty" của
  SM-A127F và vùng "Cty" của iPhone cùng tồn tại, bản đồ vẽ 2 vòng tròn chồng nhau (22/10).
  Nay `DeviceInfo.json()` trả cả `device_id` (giống iOS).
- **Android — sự kiện geofence thiếu `device_id`.** `GeofenceReceiver` (vòng tròn) và
  `PolygonGeofence` (đa giác) nay gắn `device_id` / `device_name` / `device_platform` qua
  `Util.tagDevice()`. Đo trước khi sửa: **56/56** sự kiện geofence trên server đều thiếu
  `device_id` nên viewer không lọc được theo máy.
- **Android — sự kiện diag thiếu `device_id`.** `Diag.event()` nay gắn 3 khoá thiết bị.
  Trước đây mọi dòng diag của Android nằm chung một rổ, phải suy luận thủ công qua giá trị
  `odom=` mới biết dòng nào của máy nào.
- **iOS — diag thiếu `device_name` / `device_platform`.** `diag()` chỉ gửi `platform` và
  `device_id`, trong khi Android gửi `device_name` + `device_platform`; server/viewer đọc 2
  khoá sau nên mọi dòng diag của iPhone hiện là "không rõ máy". Nay iOS gửi đủ, giữ
  `platform` để tương thích ngược.
- **iOS — sự kiện geofence thiếu `timestamp`.** Cả nhánh vòng tròn (`handleRegion`) và đa
  giác (`evaluatePolygons`) nay đặt `timestamp`. Trước đây 16 bản ghi sự kiện geofence của
  iPhone nằm trên server KHÔNG có mốc thời gian → viewer không lọc được theo ngày, không vẽ
  được điểm (Android thì có, nên hai nền tảng lệch nhau).

**Thêm mới**

- `uploadLog(maxBytes)` trên **cả Android và iOS**: đẩy phần cuối file log chẩn đoán lên
  server (`POST <url>/logs`). Trả về `{ ok, status, response, bytes, logPath }`.
  Nhờ vậy đọc được log của máy ở xa mà không phải cắm cáp `adb pull` / mở Xcode — đúng thứ
  còn thiếu khi chẩn đoán đoạn hành trình bị mất.
- `geo-server`: `POST /logs` (nhận log, lưu `data/logs/<tên>.log` + chỉ mục `data/logs.jsonl`),
  `GET /logs` (danh sách), `GET /logs/<tệp>` (nội dung, text/plain). Tên tệp được làm sạch để
  chặn path traversal; trần 8 MB. `/stats` thêm `logUploads`; trang bản đồ có liên kết tới `/logs`.
- App lab: nút **"ĐẨY LOG LÊN SERVER"** trong tab Server; phần đẩy định nghĩa geofence nay
  fallback `device_id || identifier` và gửi kèm header `x-device-id` (chống bản build cũ).

## 0.1.0 — first public release

**Android**
- Foreground-service tracking with FusedLocationProvider, elastic `distanceFilter`,
  motion detection (Activity Recognition + significant motion) and stop-detection.
- Kalman + spike-rejection filter (`filter.policy = adjust | passThrough`).
- Geofencing (circular + polygon, unlimited count with proximity loading), geofence pause mode
  (`pauseInsideGeofence`), zero-loss mode.
- SQLite queue with auto-sync (`autoSync`, `batchSync`, `maxBatchSize`), odometer,
  diagnostics log (`rnbggeo.log`) and diag events (`<url>/diag`).

**iOS (new in this release)**
- Full native port: tracking, geofencing (circular + polygon), SQLite queue, URLSession sync.
- Stable `device_id` stored in the Keychain (survives reinstalls) and a distinct
  `(Simulator)` device name.
- Tracking is restored at native init when iOS relaunches the app in the background —
  works before JS runs.
- GPS is re-armed on the first location delivered after the stationary stop.
- Geofence around the last known position (moved every `safetyGeofenceIntervalMinutes`
  while moving) so leaving it wakes the app after it was terminated.
- Background Fetch support (`backgroundFetchIntervalMinutes`, AppDelegate hook).
- `preventSuspend` option, coarse "low power" stationary GPS option, `stationaryLowPowerGps`.
- File log (`Library/Logs/rnbggeo.log`) and diag events (`<url>/diag`).
- Speed is derived from distance when CoreLocation reports an invalid speed.

**Shared**
- API compatible with `@transistorsoft/react-native-background-geolocation`
  (`ready` / `start` / `stop` / events), TypeScript definitions included.
