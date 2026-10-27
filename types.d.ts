// Type definitions for rn-bg-geolocation

export interface Coords {
  latitude: number;
  longitude: number;
  speed: number;
  heading: number;
  accuracy: number;
  altitude?: number | null;
}

export interface Activity {
  type: string;
  confidence: number;
}

export interface Battery {
  level: number;
  is_charging: boolean;
}

export interface Location {
  uuid: string;
  timestamp: number;
  event: string;
  is_moving: boolean;
  activity: Activity;
  coords: Coords;
  battery: Battery;
  odometer: number;
  mock: boolean;
}

export interface MotionChangeEvent {
  isMoving: boolean;
  activity: string;
  location?: Location;
}

export interface GeofenceEvent {
  identifier: string;
  /** Tên vùng nếu đã đặt (Nhà, Công ty...). */
  name?: string;
  /** 1 = ENTER, 2 = EXIT, 4 = DWELL (giá trị số, giữ tương thích). */
  action: 1 | 2 | 4;
  /** Tên hành động cho dễ đọc: 'ENTER' | 'EXIT' | 'DWELL'. */
  actionName?: 'ENTER' | 'EXIT' | 'DWELL';
  timestamp: number;
  /** true nếu vùng là đa giác (được chấm điểm cục bộ, không qua Geofencing API). */
  polygon?: boolean;
  extras?: Record<string, any>;
  location?: { latitude: number; longitude: number; speed: number; accuracy: number };
}

export interface Geofence {
  identifier: string;
  /** Tên hiển thị: "Nhà", "Công ty", "Bãi xe"... Dùng cho thông báo và chấm công. */
  name?: string;
  /** Tâm vùng tròn. Với đa giác, đây là tâm vòng tròn bao (tự tính). */
  latitude?: number;
  /** Kinh độ tâm. Với đa giác, đây là tâm vòng tròn bao (tự tính). */
  longitude?: number;
  /** Bán kính (m). Bắt buộc với vùng tròn; với đa giác sẽ được tự tính. */
  radius?: number;
  /**
   * Geofence ĐA GIÁC: mảng >= 3 đỉnh `[lat, lng]`.
   * Có `vertices` thì bỏ qua `latitude/longitude/radius` khi khai báo.
   * Lib chấm điểm bằng ray casting trên mỗi mẫu GPS đã qua bộ lọc.
   */
  vertices?: Array<[number, number]>;
  notifyOnEntry?: boolean;
  notifyOnExit?: boolean;
  notifyOnDwell?: boolean;
  /** Số GIÂY phải nán trong vùng trước khi bắn DWELL. Mặc định 30. */
  loiteringDelay?: number;
  /** false = không bắn thông báo hệ thống cho vùng này (vẫn bắn onGeofence). Mặc định true. */
  notification?: boolean;
  extras?: Record<string, any>;
}

export interface ActivityChangeEvent {
  activity: string;
  confidence: number;
  isMoving: boolean;
  timestamp: number;
}

export interface ConnectivityEvent {
  connected: boolean;
  type: 'wifi' | 'cellular' | 'ethernet' | 'other' | 'none';
}

export interface GeofencesChangeEvent {
  count: number;
  geofences: Geofence[];
  timestamp: number;
}

export interface PowerSaveEvent {
  isPowerSaveMode: boolean;
}

export interface GeofenceStats {
  count: number;
  max: number;
}

export interface ConnectivityInfo {
  connected: boolean;
  type: string;
}

export interface HttpEvent {
  success: boolean;
  status: number;
  responseText: string;
  count: number;
}

export interface State {
  enabled: boolean;
  /**
   * true khi tiến trình này do iOS đánh thức ở nền (significant-location-change / geofence),
   * không phải người dùng mở app. Chỉ iOS mới có; Android luôn false.
   * Dùng để tránh dựng UI nặng khi app được đánh thức trong nền.
   */
  didLaunchInBackground?: boolean;
  /** Người dùng có muốn theo dõi hay không (khác `enabled`: đứng yên thì service vẫn chạy nhưng GPS tắt). */
  desired: boolean;
  /** Service đang chạy nhưng đã tắt GPS vì đứng yên (tiết kiệm pin). */
  stationary: boolean;
  trackingMode: number;
  isMoving: boolean;
  activity: string;
  odometer: number;
  geofenceCount: number;
  pendingCount: number;
  /** Tổng số điểm đang giữ trong SQLite (kể cả đã gửi) — dùng để vẽ hành trình. */
  totalCount: number;
  distanceFilter: number;
  interval: number;
  url: string;
  autoSync: boolean;
  persist: boolean;
  stopOnTerminate: boolean;
  startOnBoot: boolean;
  schedulerEnabled: boolean;
  logToFile: boolean;
  logPath: string;
  logSize: number;
  postDiagEvents: boolean;
  /**
   * Số vệ tinh ĐANG ĐƯỢC DÙNG để tính vị trí (Android; iOS luôn là -1 vì không có API công
   * khai). Cùng với [gnssVisible] để trả lời câu hỏi chẩn đoán quan trọng nhất:
   *   - `gnssVisible` = 0  → máy KHÔNG nhìn thấy vệ tinh nào (anten/GNSS hoặc bị che sóng);
   *   - `gnssVisible` lớn mà vị trí vẫn sai/không có → lỗi phần mềm (Play Services / hệ điều hành).
   */
  gnssUsed: number;
  /** Số vệ tinh NHÌN THẤY được (Android; iOS = -1). -1 = chưa có dữ liệu. */
  gnssVisible: number;
  /** Mô tả sẵn dạng tiếng Việt, ví dụ "8/21 vệ tinh" — để hiện thẳng lên app. */
  gnssText: string;
  /**
   * iOS 17+: `CLBackgroundActivitySession` có ĐANG SỐNG không (trạng thái THẬT, không phải
   * giá trị config). true = hệ thống đang giữ app chạy nền liên tục (và status bar có
   * "viên xanh"). Android luôn trả `false` vì không có API này.
   */
  bgActivitySession: boolean;
}

export interface CurrentPositionRequest {
  timeout?: number;
  desiredAccuracy?: string;
  samples?: number;
  maximumAge?: number;
  extras?: Record<string, any>;
}

/** Bộ lọc mẫu thô — chặn điểm nhảy trước khi ghi. */
export interface FilterConfig {
  /** 'adjust' = lọc + làm mượt Kalman | 'passThrough' = chỉ kiểm tra ngưỡng. */
  policy?: 'adjust' | 'passThrough';
  /** Làm mượt vị trí bằng Kalman. Mặc định true. */
  useKalman?: boolean;
  /** Sai số ngang tối đa (m) để nhận mẫu. Mặc định 100. */
  trackingAccuracyThreshold?: number;
  /** Tốc độ suy ra tối đa (m/s) trước khi loại mẫu. Mặc định 60. */
  maxImpliedSpeed?: number;
  /** Sai số tối đa (m) để mẫu được cộng vào quãng đường. Mặc định 20. */
  odometerAccuracyThreshold?: number;
  /**
   * Khi đứng yên, chỉ ghi điểm mới nếu đã rời mốc cũ quá ngưỡng này (m).
   * Chặn GPS trôi quanh một chỗ. Mặc định 30.
   */
  stationaryDistanceThreshold?: number;
}

/** Hành vi khi đứng yên. */
export interface ActivityConfig {
  /** true = GPS chạy mãi, không tự tắt khi đứng yên. */
  disableStopDetection?: boolean;
  /** true = gọi stop() hẳn khi hết stopTimeout, thay vì chỉ tạm nghỉ. */
  stopOnStationary?: boolean;
  /**
   * Android: chờ bao nhiêu ms sau khi Motion API báo có chuyển động rồi mới chốt
   * "đang đi" và bật GPS. Nếu trước đó Motion API trả về still thì huỷ.
   * Mặc định 0 (giống lib gốc). Tăng lên 10–30 s để chống báo nhầm khi đi loanh quanh.
   */
  motionTriggerDelay?: number;
  /** Độ tin cậy tối thiểu (0–100) của Activity Recognition. Android mặc định 75. */
  minimumActivityRecognitionConfidence?: number;
  /**
   * Danh sách hoạt động được phép đánh thức (phân tách bằng dấu phẩy).
   * Mặc định: in_vehicle,on_bicycle,on_foot,running,walking (giống lib gốc).
   */
  triggerActivities?: string;
}

/** Một mẫu bị bộ lọc loại. */
export interface LocationFilterEvent {
  reason: 'accuracy' | 'speed' | 'identical' | 'time' | 'stationary' | 'geofence';
  timestamp: number;
  distance: number;
  rejected: number;
  accepted: number;
  coords: Coords;
}

export interface Config {
  distanceFilter?: number;
  interval?: number;
  desiredAccuracy?: string;
  /**
   * false = tracking vẫn sống sau khi app bị kết thúc (giống lib gốc). Mặc định `false`.
   *
   * **iOS** — đây là khoá bật cơ chế "geofence trước khi kết thúc" của lib gốc:
   * "Before termination, the SDK registers a geofence around the last known position.
   * When the device exits that geofence, iOS fully relaunches the app in the background
   * and tracking resumes automatically."
   *
   * Cụ thể trên iOS (từ 0.1.6), lib chốt lại geofence quanh vị trí cuối ở 3 thời điểm:
   *  1. khi vào trạng thái đứng yên;
   *  2. khi **rời foreground** (`appDidEnterBackground` — điểm "trước khi bị kill" thực tế,
   *     vì iOS không gọi `applicationWillTerminate` khi người dùng vuốt app khỏi đa nhiệm);
   *  3. khi app **sắp bị kết thúc** (`willTerminateNotification`).
   *
   * Ngoài ra khi đang đi, vùng còn được dời mỗi `safetyGeofenceIntervalMinutes` phút.
   * Vùng sống ở tầng OS nên **sống qua cả app bị kill và máy reboot**.
   *
   * Với `true` (mặc định của lib gốc transistorsoft, KHÁC mặc định của thư viện này):
   * lib "calls stop() as the app terminates" → khi app sắp kết thúc, vùng vị trí cuối
   * bị XOÁ để iOS không đánh thức app lên một cách vô ích.
   */
  stopOnTerminate?: boolean;
  startOnBoot?: boolean;
  motionDetection?: boolean;
  persist?: boolean;

  /** true = giữ distanceFilter cố định, không giãn theo tốc độ. Mặc định false. */
  disableElasticity?: boolean;
  /** Hệ số phóng đại elasticity. Mặc định 1.0; 0 = tắt elasticity. */
  elasticityMultiplier?: number;
  /** Nhịp lấy mẫu (ms) — chỉ có hiệu lực khi distanceFilter = 0. Mặc định 1000. */
  locationUpdateInterval?: number;
  /** Trần cứng giữa hai lần lấy mẫu (ms). Mặc định -1 = không đặt. */
  fastestLocationUpdateInterval?: number;
  /** Gộp lô (ms) trước khi giao vị trí. Mặc định 0. */
  deferTime?: number;
  /** Số giây getCurrentPosition chờ. Mặc định 60. */
  locationTimeout?: number;
  /** true = vẫn ghi điểm trùng toạ độ. Mặc định false. */
  allowIdenticalLocations?: boolean;
  /** true = chế độ siêu tiết kiệm (chỉ điểm mỗi 500–1000 m). Mặc định false. */
  useSignificantChangesOnly?: boolean;

  /** Bán kính (m) phải đi xa hơn mới tính là rời chỗ. Android mặc định 150. */
  stationaryRadius?: number;
  /** Số phút chờ sau khi báo STILL rồi mới tắt GPS. Mặc định 5. */
  stopTimeout?: number;
  /** > 0 = tự dừng sau N phút. Mặc định 0 (tắt). */
  stopAfterElapsedMinutes?: number;

  /** Bán kính (m) nạp geofence đang giám sát. Mặc định 1000. */
  geofenceProximityRadius?: number;
  /** true = thêm vùng đa giác khi đang đứng trong vùng thì bắn ENTER ngay. Mặc định true. */
  geofenceInitialTriggerEntry?: boolean;
  /** true = bật thông báo hệ thống khi vào/ra geofence. Mặc định true. */
  geofenceNotifications?: boolean;

  /**
   * iOS: chế độ thông báo geofence.
   * - `'always'` (mặc định): báo cả khi app ở nền hoặc đã bị đóng.
   * - `'foreground'`: chỉ báo khi app đang mở (đang hiển thị).
   *
   * Lưu ý: ENTER kiểu "đang ở trong vùng lúc mở app / lúc vừa tạo vùng" (`initial`)
   * LUÔN không báo — đó không phải là "vừa đến", và trước 22/10/2026 chính nó làm mỗi
   * lần mở app lại có thông báo "Đã đến <tên vùng>".
   */
  geofenceNotificationMode?: 'always' | 'foreground';

  /**
   * iOS: nhịp gửi diag `alive` lên `<url>/diag` (phút). Mặc định 15, 0 = tắt.
   * Giống Android — nhờ nó đọc /diag là biết app còn sống hay đã bị iOS treo/giết.
   * Lần đầu gửi ở nhịp tim đầu tiên (~60 s sau khi bật).
   */
  aliveIntervalMinutes?: number;

  /**
   * iOS: khi đã tạm dừng vì đứng yên TRONG geofence (GPS tắt), cứ N phút lấy MỘT mẫu thô
   * (100 m) để biết còn trong vùng hay đã ra ngoài — rồi hoặc ngủ tiếp, hoặc bật tracking
   * lại. Giống Android. Mặc định 10, 0 = tắt.
   */
  insideWatchdogMinutes?: number;
  /**
   * WATCHDOG "nhà cung cấp vị trí im lặng" (Android, mặc định 3 phút, 0 = tắt).
   *
   * Khi app cho là ĐANG DI CHUYỂN mà không nhận được mẫu vị trí nào trong N phút, watchdog:
   *  1. ghi một sự kiện diag `gap` lên server (`<url>/diag`) — nhìn thấy được từ xa, không
   *     cần cắm cáp đọc file log;
   *  2. "đá" lại nhà cung cấp: đăng ký lại request vị trí + gọi `getCurrentLocation` một lần.
   *
   * Vì sao cần: đo thật 23/10 trên SM-A127F — request vị trí đang hoạt động, service sống,
   * mạng tốt, mà FusedLocationProvider trả về 0 mẫu suốt 10 phút ⇒ mất 4,8 km hành trình.
   * Máy đó còn bị y hệt 2 lần nữa trong ngày 22/10 (9,0 phút và 5,7 phút).
   */
  providerSilenceMinutes?: number;
  /**
   * true = KHÔNG ghi điểm khi ĐỨNG YÊN trong bất kỳ geofence nào.
   * Chỉ theo dõi geofence (giống tinh thần startGeofences của lib gốc), tránh GPS trôi
   * trong nhà/văn phòng sinh ra chùm điểm.
   *
   * Đang DI CHUYỂN trong vùng thì VẪN ghi điểm — nếu bỏ mọi mẫu trong vùng thì đoạn
   * ~bán kính cuối lúc về và đoạn đầu lúc rời đi sẽ bị mất (đã xảy ra 19–20/10/2026).
   * Mặc định false.
   */
  pauseInsideGeofence?: boolean;

  /**
   * true = GPS KHÔNG BAO GIỜ tắt vì đứng yên (bỏ stop-detection và bỏ tạm nghỉ theo
   * geofence). Dùng khi cần không có khoảng trắng nào giữa các chuyến.
   *
   * Độc lập với `pauseInsideGeofence`: công tắc "không ghi điểm trong vùng" vẫn hoạt
   * động bình thường — nó quyết định CÓ GHI ĐIỂM hay không, còn zeroLossMode quyết
   * định GPS CÓ TẮT hay không.
   *
   * Đánh đổi: GNSS bật liên tục nên hao pin hơn đáng kể. Mặc định false.
   */
  zeroLossMode?: boolean;

  /**
   * iOS: khi đứng yên quá stopTimeout, thay vì TẮT HẲN startUpdatingLocation thì hạ
   * xuống accuracy thô (hundredMeters) + distanceFilter 100 m ("ngủ đông").
   *
   * ⚠️ UX: bật true thì iOS LUÔN hiện mũi tên vị trí trên status bar, kể cả khi máy
   * đứng yên — người dùng thường không thích. Mặc định false: tắt hẳn GPS (mũi tên
   * biến mất) và lib tự đăng ký một "geofence đứng yên" để iOS đánh thức app khi máy
   * rời chỗ, giống cách lib gốc của transistorsoft làm.
   *
   * Chỉ bật true khi cần bắt chuyển động trong ~100 m và chấp nhận hao pin + mũi tên.
   * `zeroLossMode: true` (GPS chính xác cao liên tục) cũng làm mũi tên hiện mãi.
   *
   * Chỉ có tác dụng trên iOS (Android bỏ qua).
   */
  stationaryLowPowerGps?: boolean;

  /**
   * iOS: khi **đang di chuyển**, dời geofence "vị trí cuối" mỗi N phút (mặc định 5,
   * 0 = tắt). Đây là mô hình của lib gốc transistorsoft: một geofence quanh vị trí
   * cuối sống ở tầng OS, nên nếu app bị kill giữa đường thì khi ra khỏi vùng iOS sẽ
   * relaunch app ở nền và tracking tự khôi phục.
   *
   * Bán kính dùng `stationaryRadius` (tối thiểu 100 m). Chiếm 1 trong 20 slot region
   * của iOS — lib tự chừa chỗ, không cần cấu hình thêm.
   */
  safetyGeofenceIntervalMinutes?: number;

  /**
   * iOS: giữ app KHÔNG bị hệ thống treo khi đã tắt GPS (tương đương `preventSuspend`
   * của lib gốc).
   *
   * Cách làm: khi vào trạng thái đứng yên, thay vì tắt hẳn location-services, lib giữ
   * session location ở độ chính xác thô → iOS coi app đang hoạt động nền nên không
   * treo; JS và `onHeartbeat` vẫn chạy.
   *
   * ⚠️ Đánh đổi (đúng như tài liệu lib gốc): hao pin rõ rệt và **mũi tên vị trí hiện
   * liên tục** trên status bar. Chỉ bật cho trường hợp đặc biệt (máy cắm sạc, cần
   * heartbeat đều). Mặc định false.
   */
  preventSuspend?: boolean;

  /**
   * iOS: nhịp Background Fetch tính bằng phút (mặc định 15, `0` = tắt).
   *
   * iOS gọi `application(_:performFetchWithCompletionHandler:)` khoảng 15 phút/lần —
   * kể cả khi app đã bị treo hoặc sau reboot — và lib mở GPS ~20 giây để lấy một mẫu.
   * Đây là nguồn đánh thức phụ giống lib gốc.
   *
   * Yêu cầu phía app: `UIBackgroundModes` có `fetch` và AppDelegate chuyển tiếp:
   * ```swift
   * override func application(_ application: UIApplication,
   *                           performFetchWithCompletionHandler completionHandler:
   *                           @escaping (UIBackgroundFetchResult) -> Void) {
   *   BgGeo.handleBackgroundFetch(completionHandler)
   * }
   * ```
   */
  backgroundFetchIntervalMinutes?: number;

  /**
   * iOS 17+: dùng `CLBackgroundActivitySession` (giữ app chạy nền liên tục).
   *
   * MẶC ĐỊNH `false` (0.1.8, 27/10/2026) — theo triết lý "pin trước" của lib gốc và phù hợp
   * app CHẤM CÔNG: không hiện "viền xanh" thường trực, đứng yên thì tắt hẳn GPS.
   *
   * Đặt `true` khi cần **vẽ hành trình liền mạch**: session giữ app không bị iOS treo khi
   * location đang hoạt động. Đo thật 26/10/2026: mở app (foreground) rồi khoá màn hình với
   * session BẬT ⇒ ghi **682 điểm / 9,18 km / 22 phút**, odometer khớp đường vẽ **99,8 %**.
   * Đổi lại: status bar LUÔN hiện "viền xanh" (Apple: session *"manages a visual indicator
   * that keeps your app in use in the background"*) — không tắt được.
   *
   * Vì sao chấm công KHÔNG cần: ENTER/EXIT vùng do lib tự chấm trên từng mẫu GPS
   * (point-in-circle / point-in-polygon); native region chỉ là "chuông đánh thức". Khi người
   * dùng di chuyển, app đã thức (do vùng "vị trí cuối" đánh thức) ⇒ mốc VÀO vùng chính xác
   * ~1–2 giây. Mốc RỜI vùng trễ ~200 m vì iOS chỉ đánh thức app sau ~200 m di chuyển —
   * giới hạn của hệ điều hành, đánh đổi để đứng yên không tốn pin.
   *
   * Trạng thái THẬT của session đọc qua `getState().bgActivitySession`.
   */
  backgroundActivitySession?: boolean;

  /**
   * iOS: hiện mũi tên vị trí trên status bar khi app dùng location ở nền.
   * Mặc định false (giống lib gốc) — bật true nếu muốn người dùng thấy app đang
   * dùng vị trí. Android không dùng khoá này.
   */
  showsBackgroundLocationIndicator?: boolean;

  filter?: FilterConfig;
  activity?: ActivityConfig;

  notificationTitle?: string;
  notificationText?: string;
  notificationIcon?: string;
  /** true = notification hiện toạ độ/tốc độ (cập nhật 1 lần/phút); false = chỉ trạng thái */
  notificationLiveCoords?: boolean;
  /** true (mặc định) = notification tối giản, không icon trên thanh trạng thái */
  notificationMinimal?: boolean;
  url?: string;
  autoSync?: boolean;
  autoSyncThreshold?: number;
  batchSync?: boolean;
  maxBatchSize?: number;
  httpRootProperty?: string;
  headers?: Record<string, string>;
  params?: Record<string, any>;
  /** Ghi nhật ký chẩn đoán ra file trong thư mục ngoài của app (adb pull được). Mặc định false. */
  logToFile?: boolean;
  /** Ngưỡng xoay file log, byte. Mặc định 16 MB. */
  logMaxBytes?: number;
  /** Đẩy sự kiện service/motion/geofence lên `url + "/diag"`. Mặc định false. */
  postDiagEvents?: boolean;
}

export interface Subscription {
  remove(): void;
}

export const DesiredAccuracy: { High: string; Balanced: string; Low: string; Passive: string };
export const LogLevel: { Off: number; Error: number; Warning: number; Info: number; Debug: number; Verbose: number };
export const ActivityType: { Still: string; Walking: string; Running: string; InVehicle: string; OnBicycle: string; OnFoot: string; Unknown: string };
export const GeofenceEvent: { Enter: 1; Exit: 2; Dwell: 4 };
export const LocationFilterPolicy: { Adjust: string; PassThrough: string };
export const LocationFilterReason: { Accuracy: string; Speed: string; Identical: string; Time: string; Stationary: string; Geofence: string };

export function onLocation(cb: (location: Location) => void): Subscription;
export function onMotionChange(cb: (event: MotionChangeEvent) => void): Subscription;
export function onGeofence(cb: (event: GeofenceEvent) => void): Subscription;
export function onHttp(cb: (event: HttpEvent) => void): Subscription;
export function onEnabledChange(cb: (event: { enabled: boolean }) => void): Subscription;
export function onActivityChange(cb: (event: ActivityChangeEvent) => void): Subscription;
export function onConnectivity(cb: (event: ConnectivityEvent) => void): Subscription;
export function onGeofencesChange(cb: (event: GeofencesChangeEvent) => void): Subscription;
export function onPowerSaveChange(cb: (event: PowerSaveEvent) => void): Subscription;
export function onLocationFilter(cb: (event: LocationFilterEvent) => void): Subscription;
export function onHeartbeat(cb: (event: HeartbeatEvent) => void): Subscription;
export function onProviderChange(cb: (event: ProviderChangeEvent) => void): Subscription;
export function onAuthorization(cb: (event: ProviderChangeEvent) => void): Subscription;
export function onSchedule(cb: (event: ScheduleEvent) => void): Subscription;
export function removeAllListeners(): void;

export function ready(config?: Config): Promise<State>;
export function start(): Promise<State>;
export function stop(): Promise<State>;
export function getState(): Promise<State>;
export function getConfig(): Promise<Config>;
export function reset(config?: Config): Promise<State>;
export function setConfig(partial: Config): Promise<State>;
export function getCurrentPosition(options?: CurrentPositionRequest): Promise<Location>;
export function changePace(isMoving: boolean): Promise<State>;
export function addGeofence(geofence: Geofence): Promise<string>;
export function addGeofences(list: Geofence[]): Promise<number>;
export function removeGeofence(identifier: string): Promise<boolean>;
export function removeGeofences(identifiers?: string[]): Promise<boolean>;
export function getGeofences(): Promise<Geofence[]>;
export function getGeofence(identifier: string): Promise<Geofence | null>;
export function geofenceExists(identifier: string): Promise<boolean>;
export function getLocations(): Promise<Location[]>;
export function getCount(): Promise<number>;
export function sync(): Promise<boolean>;
export function destroyLocations(): Promise<boolean>;
export function destroyLocation(uuid: string): Promise<number>;
export function insertLocation(location: Partial<Location> & { coords: Coords }): Promise<string>;
export function hasPermission(): Promise<{ fine: boolean; background: boolean }>;
export function requestPermission(): Promise<ProviderChangeEvent>;
export function getProviderState(): Promise<ProviderChangeEvent>;
export function getDeviceInfo(): Promise<DeviceInfo>;
export function getSensors(): Promise<SensorInfo[]>;
export function getOdometer(): Promise<number>;
export function setOdometer(value: number): Promise<number>;
export function resetOdometer(): Promise<boolean>;
export function getGeofenceStats(): Promise<GeofenceStats>;
export function isPowerSaveMode(): Promise<boolean>;
export function getConnectivityInfo(): Promise<ConnectivityInfo>;
export function startBackgroundTask(): Promise<number>;
export function endBackgroundTask(taskId?: number): Promise<boolean>;
export function startWatchPosition(options?: Partial<CurrentPositionRequest> & { interval?: number; distanceFilter?: number }): Promise<boolean>;
export function stopWatchPosition(): Promise<boolean>;
export function watchPosition(
  options: Partial<CurrentPositionRequest> & { interval?: number; distanceFilter?: number },
  cb: (location: Location) => void
): Subscription;
export function getSchedule(): Promise<ScheduleInfo>;
export function startSchedule(): Promise<ScheduleInfo>;
export function stopSchedule(): Promise<ScheduleInfo>;
export function getLogPath(): Promise<string>;
export function getLogSize(): Promise<number>;
export function getLog(maxBytes?: number): Promise<string>;
export function clearLog(): Promise<boolean>;
export function destroyLog(): Promise<boolean>;
/**
 * Đẩy file log chẩn đoán lên server (`POST <url>/logs`) — đọc log của máy ở xa mà không
 * cần cắm cáp. Có trên cả Android và iOS.
 */
export function uploadLog(maxBytes?: number): Promise<LogUploadResult>;
export function setLogLevel(level: number): Promise<number>;
export function playSound(kind?: 'location' | 'motion' | 'error'): Promise<boolean>;

/** Nhịp tim mỗi `heartbeatInterval` giây. */
export interface HeartbeatEvent {
  timestamp: number;
  location?: Location;
  battery?: number;
  odometer?: number;
}

/** Payload của onProviderChange / onAuthorization. */
export interface ProviderChangeEvent {
  enabled: boolean;
  gps: boolean;
  network: boolean;
  /** 0 = chưa cấp, 2 = khi đang dùng, 3 = luôn cho phép */
  status: number;
  /** 1 = chính xác đầy đủ, 0 = tương đối */
  accuracyAuthorization: number;
  timestamp: number;
}

export interface ScheduleEvent {
  active: boolean;
}

export interface ScheduleInfo {
  enabled: boolean;
  active: boolean;
  spec: string[];
}

export interface DeviceInfo {
  platform: string;
  model: string;
  manufacturer: string;
  version: string;
  sdk?: number;
  device: string;
  brand: string;
  product: string;
  framework: string;
  /** Mã ổn định đang dùng làm `device_id` gửi lên server (cả Android và iOS). */
  device_id: string;
  identifier: string;
}

/** Kết quả `uploadLog()`. */
export interface LogUploadResult {
  ok: boolean;
  /** Mã HTTP server trả về (0 = không kết nối được). */
  status: number;
  /** Nội dung phản hồi của server (JSON dạng chuỗi). */
  response: string;
  /** Số ký tự log đã gửi đi. */
  bytes: number;
  /** Đường dẫn file log trên máy. */
  logPath: string;
}

export interface SensorInfo {
  name: string;
  type: number;
  vendor: string;
  version: number;
  power: number;
  maxRange: number;
  resolution: number;
}

declare const BgGeo: {
  DesiredAccuracy: typeof DesiredAccuracy;
  LogLevel: typeof LogLevel;
  ActivityType: typeof ActivityType;
  GeofenceEvent: typeof GeofenceEvent;
  LocationFilterPolicy: typeof LocationFilterPolicy;
  LocationFilterReason: typeof LocationFilterReason;
  onLocation: typeof onLocation;
  onMotionChange: typeof onMotionChange;
  onGeofence: typeof onGeofence;
  onHttp: typeof onHttp;
  onEnabledChange: typeof onEnabledChange;
  onActivityChange: typeof onActivityChange;
  onConnectivity: typeof onConnectivity;
  onGeofencesChange: typeof onGeofencesChange;
  onPowerSaveChange: typeof onPowerSaveChange;
  onLocationFilter: typeof onLocationFilter;
  onHeartbeat: typeof onHeartbeat;
  onProviderChange: typeof onProviderChange;
  onAuthorization: typeof onAuthorization;
  onSchedule: typeof onSchedule;
  removeAllListeners: typeof removeAllListeners;
  ready: typeof ready;
  start: typeof start;
  stop: typeof stop;
  getState: typeof getState;
  getConfig: typeof getConfig;
  reset: typeof reset;
  setConfig: typeof setConfig;
  getCurrentPosition: typeof getCurrentPosition;
  changePace: typeof changePace;
  addGeofence: typeof addGeofence;
  addGeofences: typeof addGeofences;
  removeGeofence: typeof removeGeofence;
  removeGeofences: typeof removeGeofences;
  getGeofences: typeof getGeofences;
  getGeofence: typeof getGeofence;
  geofenceExists: typeof geofenceExists;
  getLocations: typeof getLocations;
  getCount: typeof getCount;
  sync: typeof sync;
  destroyLocations: typeof destroyLocations;
  destroyLocation: typeof destroyLocation;
  insertLocation: typeof insertLocation;
  hasPermission: typeof hasPermission;
  requestPermission: typeof requestPermission;
  getProviderState: typeof getProviderState;
  getDeviceInfo: typeof getDeviceInfo;
  getSensors: typeof getSensors;
  getOdometer: typeof getOdometer;
  setOdometer: typeof setOdometer;
  resetOdometer: typeof resetOdometer;
  getGeofenceStats: typeof getGeofenceStats;
  isPowerSaveMode: typeof isPowerSaveMode;
  getConnectivityInfo: typeof getConnectivityInfo;
  startBackgroundTask: typeof startBackgroundTask;
  endBackgroundTask: typeof endBackgroundTask;
  startWatchPosition: typeof startWatchPosition;
  stopWatchPosition: typeof stopWatchPosition;
  watchPosition: typeof watchPosition;
  getSchedule: typeof getSchedule;
  startSchedule: typeof startSchedule;
  stopSchedule: typeof stopSchedule;
  getLogPath: typeof getLogPath;
  getLogSize: typeof getLogSize;
  getLog: typeof getLog;
  clearLog: typeof clearLog;
  destroyLog: typeof destroyLog;
  uploadLog: typeof uploadLog;
  setLogLevel: typeof setLogLevel;
  playSound: typeof playSound;
};

export default BgGeo;
