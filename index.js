/**
 * rn-bg-geolocation — free background geolocation for React Native (Android).
 *
 * API intentionally mirrors @transistorsoft/react-native-background-geolocation
 * (ready/start/stop/onLocation/onMotionChange/onGeofence...) so most example
 * code for that SDK works by only changing the import.
 *
 * NO LICENSE REQUIRED. Everything runs on plain Android APIs:
 * FusedLocationProvider + ForegroundService + Activity Recognition + SQLite + OkHttp.
 */
'use strict';

const { NativeEventEmitter, NativeModules, Platform } = require('react-native');

const native = NativeModules.BgGeo;

if (!native) {
  // iOS: chưa có implementation native. Không throw để phần còn lại của app vẫn chạy;
  // mọi API sẽ reject với thông báo rõ ràng. Xem docs/IOS-PORT.md.
  console.warn(
    '[rn-bg-geolocation] chưa có native module cho ' + Platform.OS +
      ' — API sẽ reject. (Android đã có; iOS đang chờ port.)'
  );
}

// ---------------------------------------------------------------- constants
const DesiredAccuracy = {
  High: 'high',
  Balanced: 'balanced',
  Low: 'low',
  Passive: 'passive',
};

const LogLevel = {
  Off: 0,
  Error: 1,
  Warning: 2,
  Info: 3,
  Debug: 4,
  Verbose: 5,
};

const ActivityType = {
  Still: 'still',
  Walking: 'walking',
  Running: 'running',
  InVehicle: 'in_vehicle',
  OnBicycle: 'on_bicycle',
  OnFoot: 'on_foot',
  Unknown: 'unknown',
};

// trùng giá trị với Geofence.GEOFENCE_TRANSITION_* bên native
const GeofenceEvent = {
  Enter: 1,
  Exit: 2,
  Dwell: 4,
};

/** Chính sách của bộ lọc mẫu thô. */
const LocationFilterPolicy = {
  Adjust: 'adjust', // lọc + làm mượt Kalman
  PassThrough: 'passThrough', // chỉ kiểm tra ngưỡng, không làm mượt
};

/** Lý do một mẫu bị bộ lọc loại — khớp với giá trị native bắn lên. */
const LocationFilterReason = {
  Accuracy: 'accuracy', // sai số ngang quá lớn
  Speed: 'speed', // tốc độ suy ra bất khả thi → điểm nhảy
  Identical: 'identical', // trùng toạ độ mẫu trước
  Time: 'time', // hai mẫu cùng thời điểm mà lệch xa
  Stationary: 'stationary', // đứng yên, GPS trôi chưa đủ xa mốc cũ
  Geofence: 'geofence', // đang trong vùng geofence — chế độ không ghi điểm
};

// ---------------------------------------------------------------- helpers
function parseMaybeJson(value, fallback) {
  if (typeof value !== 'string') return value;
  try {
    return JSON.parse(value);
  } catch (e) {
    return fallback !== undefined ? fallback : null;
  }
}

function subscriptionList() {
  return [];
}

// ---------------------------------------------------------------- events
const emitter = native ? new NativeEventEmitter(native) : null;
const subs = subscriptionList();

function onLocation(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:location', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

function onMotionChange(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:motionchange', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

function onGeofence(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:geofence', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

function onHttp(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:http', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

function onEnabledChange(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:enabledchange', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

function onActivityChange(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:activitychange', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

function onConnectivity(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:connectivity', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

function onGeofencesChange(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:geofenceschange', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

function onPowerSaveChange(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:powersavechange', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

/** Nhịp tim: {location, battery, odometer, timestamp} mỗi `heartbeatInterval` giây. */
function onHeartbeat(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:heartbeat', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

/** GPS bật/tắt, provider thay đổi: {enabled, gps, network, status, accuracyAuthorization}. */
function onProviderChange(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:providerchange', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

/** Quyền vị trí thay đổi (cùng payload với onProviderChange). */
function onAuthorization(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:authorization', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

/** Lịch chạy: {active: boolean}. */
function onSchedule(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:schedule', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

function removeAllListeners() {
  subs.forEach((s) => s.remove());
  subs.length = 0;
}

/**
 * Bắn khi một mẫu GPS bị bộ lọc loại (accuracy kém / tốc độ vô lý / trùng vị trí).
 * Dùng để biết "vì sao mất điểm" thay vì đoán.
 */
function onLocationFilter(cb) {
  if (!native) return { remove() {} };
  const s = emitter.addListener('rnbggeo:locationfilter', (e) => {
    cb(parseMaybeJson(e.payload));
  });
  subs.push(s);
  return s;
}

// ---------------------------------------------------------------- default config
const defaultConfig = {
  distanceFilter: 10, // mét — chỉ ghi điểm mới khi đi được bán kính này
  interval: 5000, // ms — nhịp cập nhật GPS khi đang chuyển động
  desiredAccuracy: DesiredAccuracy.High,
  stopOnTerminate: false, // false = vẫn chạy nền sau khi swipe app khỏi recent. iOS: BẬT cơ chế "geofence trước khi kết thúc" của lib gốc — chốt vùng quanh vị trí cuối mỗi lần app RỜI FOREGROUND và khi sắp bị kill, nên OS relaunch app khi rời khỏi vùng. true = xoá vùng đó lúc app kết thúc (OS không đánh thức lên nữa).
  startOnBoot: true, // tự chạy lại sau reboot
  motionDetection: true, // Activity Recognition → đứng yên thì tắt GPS (tiết kiệm pin)
  persist: true, // ghi vào SQLite + hàng đợi gửi server

  // --- lấy mẫu (tương đương GeoConfig của transistorsoft) ---
  disableElasticity: false, // true = giữ distanceFilter cố định, không giãn theo tốc độ
  elasticityMultiplier: 1.0, // tăng lên thì mẫu thưa hơn khi chạy nhanh; 0 = tắt elasticity
  locationUpdateInterval: 1000, // ms — chỉ dùng khi distanceFilter = 0
  fastestLocationUpdateInterval: -1, // ms — trần cứng, -1 = không đặt
  deferTime: 0, // ms — gộp lô, nên đặt >= 2 × locationUpdateInterval
  locationTimeout: 60, // giây — getCurrentPosition chờ tối đa
  allowIdenticalLocations: false, // true = vẫn ghi điểm trùng toạ độ
  useSignificantChangesOnly: false, // true = chỉ lấy điểm mỗi 500–1000 m

  // --- stop-detection ---
  stationaryRadius: 150, // m — đi xa hơn mới tính là đã rời chỗ (Android: 25..1000)
  stopTimeout: 5, // phút — chờ sau khi Activity Recognition báo STILL rồi mới tắt GPS
  stopAfterElapsedMinutes: 0, // >0 = tự dừng sau N phút (0 = tắt)

  // --- geofencing ---
  geofenceProximityRadius: 1000, // m — chỉ nạp vùng trong bán kính này → không giới hạn số vùng
  geofenceInitialTriggerEntry: true, // đang ở trong vùng khi thêm → bắn ENTER ngay
  // MẶC ĐỊNH TẮT (từ 0.1.12): lib chỉ lo LẤY VỊ TRÍ CHÍNH XÁC + BẮN SỰ KIỆN ENTER/EXIT + GHI HÀNH TRÌNH.
  // Việc hiện thông báo là của APP — nội dung, navigation/deep-link và nghiệp vụ ("đã chấm công chưa",
  // giờ ca…) đều là chuyện của app, lib generic không biết. Bật `true` nếu muốn lib tự hiện thông báo
  // native (đặt nội dung qua `extras.entryTitle`/`exitTitle`); chế độ đó nổ được cả khi JS đã chết —
  // thực tế chỉ Android cần, vì iOS không relaunch app sau khi user gạt app khỏi đa nhiệm.
  geofenceNotifications: false,
  geofenceNotificationMode: 'always', // iOS: 'always' = báo cả khi app ở nền/đang đóng; 'foreground' = chỉ báo khi app đang mở. ENTER "đang ở trong vùng lúc mở app / lúc tạo vùng" LUÔN không báo.
  // ĐỆM SỰ KIỆN VÙNG khi JS chưa đăng ký listener (giây). Bắt buộc phải có vì app thường gọi
  // `await ready()` TRƯỚC khi đăng ký `onGeofence`, mà ready() lại syncRegions() → sự kiện bắn ngay
  // lúc đó; nếu bỏ thì app mất sự kiện (đo thật 31/10: EXIT lúc 11:38 app không hề nhận được).
  // Sự kiện cũ hơn ngưỡng này bị BỎ khi trả lại (ENTER/EXIT từ vài tiếng trước không còn giá trị
  // hành động). 0 = tắt hẳn việc đệm (quay lại hành vi cũ: mất sự kiện).
  eventBufferSeconds: 600,
  aliveIntervalMinutes: 15, // iOS: nhịp gửi diag "alive" lên /diag (phút), giống Android 15 phút/lần; 0 = tắt. Nhờ nó biết app còn sống hay đã bị iOS treo/giết.
  insideWatchdogMinutes: 10, // iOS: khi đã tạm dừng vì đứng yên TRONG vùng, cứ N phút lấy 1 mẫu thô để biết còn trong vùng hay đã đi (giống Android); 0 = tắt.
  providerSilenceMinutes: 3, // WATCHDOG "nhà cung cấp vị trí im lặng" (Android): đang di chuyển mà KHÔNG nhận được mẫu nào trong N phút → ghi diag "gap" lên server + đăng ký lại request + gọi getCurrentLocation một lần để "đá" provider. Đo thật 23/10: SM-A127F im lặng 10 phút, mất 4,8 km. 0 = tắt.
  pauseInsideGeofence: false, // true = đứng yên trong vùng thì không ghi điểm (đang đi vẫn ghi)
  zeroLossMode: false, // true = GPS không bao giờ tắt vì đứng yên (không mất điểm, hao pin hơn). Vẫn tôn trọng pauseInsideGeofence.
  stationaryLowPowerGps: false, // iOS: true = đứng yên vẫn giữ GPS thô 100 m (mũi tên vị trí luôn hiện trên status bar). false = tắt hẳn GPS + đăng ký geofence đứng yên để OS đánh thức.
  safetyGeofenceIntervalMinutes: 5, // iOS: khi đang đi, dời geofence "vị trí cuối" mỗi N phút để OS còn vùng đánh thức nếu app bị kill giữa đường. 0 = tắt. Vùng này còn được chốt lại NGAY mỗi lần app rời foreground / sắp kết thúc (xem stopOnTerminate) nên không phụ thuộc nhịp N phút.
  preventSuspend: false, // iOS: giữ app sống khi đứng yên (như lib gốc). Tốn pin + mũi tên vị trí hiện liên tục.
  backgroundFetchIntervalMinutes: 15, // iOS: nhịp Background Fetch (phút), 0 = tắt. iOS thực tế ~15 phút/lần; cần hook AppDelegate.
  backgroundActivitySession: false, // iOS 17+: TẮT mặc định (0.1.8) — KHÔNG hiện "viền xanh", đứng yên thì tắt hẳn GPS (pin trước, như lib gốc). Chỉ bật true khi cần VẼ hành trình liền mạch (đo thật 26/10: mở app rồi khoá màn hình ⇒ 682 điểm/9,18 km, odometer khớp 99,8%). Chấm công KHÔNG cần: ENTER/EXIT vùng do ta chấm trên từng mẫu GPS.

  // --- nhịp tim & lịch chạy (giống lib gốc) ---
  heartbeatInterval: 60, // giây — onHeartbeat; tối thiểu 15
  schedule: [], // vd ["1-5 08:00-17:30"]; rỗng = chạy 24/7

  // --- chính sách lưu trữ (giống PersistenceConfig) ---
  maxDaysToPersist: -1, // >0 = xoá bản ghi ĐÃ GỬI cũ hơn N ngày
  maxRecordsToPersist: -1, // >0 = chỉ giữ N bản ghi mới nhất

  // --- bộ lọc mẫu thô ---
  filter: {
    policy: 'adjust', // 'adjust' = lọc + làm mượt | 'passThrough' = chỉ kiểm tra ngưỡng
    useKalman: true, // làm mượt vị trí bằng Kalman
    trackingAccuracyThreshold: 100, // m — sai số ngang tối đa để nhận mẫu
    maxImpliedSpeed: 60, // m/s — nhanh hơn coi là điểm nhảy, loại
    odometerAccuracyThreshold: 20, // m — sai số tối đa để được cộng vào quãng đường
    stationaryDistanceThreshold: 30, // m — khi đứng yên, phải rời mốc cũ quá ngưỡng này mới ghi
  },

  // --- hành vi khi đứng yên (giống transistorsoft) ---
  activity: {
    disableStopDetection: false, // true = GPS chạy mãi, không tự tắt
    stopOnStationary: false, // true = gọi stop() hẳn thay vì tạm nghỉ
    motionTriggerDelay: 0, // ms — chờ trước khi chốt "đang đi" (Android, giống lib gốc)
    minimumActivityRecognitionConfidence: 75, // % — Android mặc định 75 (iOS 70)
    triggerActivities: 'in_vehicle,on_bicycle,on_foot,running,walking', // loại hoạt động được phép đánh thức
  },

  notificationTitle: 'rn-bg-geolocation',
  notificationText: 'Đang theo dõi vị trí…',
  notificationIcon: '',
  // true = hiện toạ độ + tốc độ trong notification (cập nhật 1 lần/phút).
  // false (mặc định) = chỉ hiện trạng thái + số điểm chờ, notification đứng yên.
  notificationLiveCoords: false,
  // true (mặc định) = notification tối giản, KHÔNG có icon trên thanh trạng thái
  // (chỉ nằm trong mục "Thông báo im lặng"). false = hiện icon như bản cũ.
  notificationMinimal: true,
  // http
  url: '',
  autoSync: true, // tự động gửi điểm lên server
  autoSyncThreshold: 0, // gửi ngay khi có điểm mới (0 = không đợi gộp)
  batchSync: false, // true = gộp nhiều điểm trong 1 request
  maxBatchSize: -1,
  httpRootProperty: 'location',
  headers: {},
  params: {},
  // nhật ký chẩn đoán (tuỳ chọn, mặc định tắt)
  logToFile: false, // true = ghi file trong thư mục ngoài của app (adb pull được)
  logMaxBytes: 16 * 1024 * 1024, // vượt ngưỡng thì xoay sang rnbggeo.1.log
  postDiagEvents: false, // true = đẩy sự kiện (service/motion/geofence) lên url + "/diag"
};

// ---------------------------------------------------------------- public API
const BgGeo = {
  DesiredAccuracy,
  LogLevel,
  ActivityType,
  GeofenceEvent,
  LocationFilterPolicy,
  LocationFilterReason,

  onLocation,
  onMotionChange,
  onGeofence,
  onHttp,
  onEnabledChange,
  onActivityChange,
  onConnectivity,
  onGeofencesChange,
  onPowerSaveChange,
  onLocationFilter,
  onHeartbeat,
  onProviderChange,
  onAuthorization,
  onSchedule,
  removeAllListeners,

  /**
   * Cấu hình SDK + khôi phục trạng thái đã lưu. Gọi 1 lần khi app khởi động,
   * TRƯỚC khi gọi start(). ready() không tự BẬT tracking mới; nhưng nếu lần chạy
   * trước đang bật và `stopOnTerminate: false` thì nó tự bật lại (giống lib gốc).
   * Trả về state — `state.enabled` cho biết có cần gọi start() hay không.
   */
  ready(config = {}) {
    if (!native) return Promise.reject(new Error('Android only'));
    const merged = Object.assign({}, defaultConfig, config);
    return native.ready(merged).then((s) => parseMaybeJson(s));
  },

  start() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.start().then((s) => parseMaybeJson(s));
  },

  stop() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.stop().then((s) => parseMaybeJson(s));
  },

  getState() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getState().then((s) => parseMaybeJson(s));
  },

  setConfig(partial) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.setConfig(partial).then((s) => parseMaybeJson(s));
  },

  getCurrentPosition(options = {}) {
    if (!native) return Promise.reject(new Error('Android only'));
    const opts = Object.assign({ timeout: 30000, desiredAccuracy: DesiredAccuracy.High }, options);
    return native.getCurrentPosition(opts).then((r) => parseMaybeJson(r.location));
  },

  /** true = buộc bật GPS (workout mode), false = quay về chế độ đứng yên */
  changePace(isMoving) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.changePace(Boolean(isMoving)).then((s) => parseMaybeJson(s));
  },

  addGeofence(geofence) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.addGeofence(geofence);
  },

  addGeofences(list) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.addGeofences(list);
  },

  removeGeofence(identifier) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.removeGeofence(identifier);
  },

  removeGeofences(identifiers) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.removeGeofences(
      identifiers && identifiers.length ? Array.from(identifiers) : null
    );
  },

  getGeofences() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getGeofences().then((s) => parseMaybeJson(s, []));
  },

  /** Một vùng theo identifier (null nếu không có). */
  getGeofence(identifier) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getGeofence(identifier).then((s) => parseMaybeJson(s, null));
  },

  /** true/false. */
  geofenceExists(identifier) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.geofenceExists(identifier);
  },

  /** lịch sử điểm trong SQLite (mới nhất trước) */
  getLocations() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getLocations().then((s) => {
      const arr = parseMaybeJson(s, []);
      if (!Array.isArray(arr)) return [];
      // iOS trả về mảng CHUỖI json (mỗi dòng SQLite là 1 chuỗi) → phải parse từng phần tử.
      // Android trả mảng object sẵn. Thiếu bước này thì Hành trình luôn 0 điểm trên iOS.
      return arr
        .map((x) => (typeof x === 'string' ? parseMaybeJson(x, null) : x))
        .filter((x) => x && x.coords);
    });
  },

  /** số điểm đang chờ gửi server (hàng đợi offline) */
  getCount() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getCount();
  },

  sync() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.sync();
  },

  destroyLocations() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.destroyLocations();
  },

  hasPermission() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.hasPermission().then((s) =>
      parseMaybeJson(s, { fine: false, background: false })
    );
  },

  // ---------------------------------------------------------- odometer

  /** Đọc quãng đường tích luỹ (met). */
  getOdometer() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getOdometer();
  },

  /** Đặt lại odometer về 0. */
  resetOdometer() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.resetOdometer();
  },

  // ---------------------------------------------------------- geofence stats

  /** Số vùng geofence đang giám sát + giới hạn tối đa. */
  getGeofenceStats() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getGeofenceStats().then((s) => parseMaybeJson(s, { count: 0, max: 100 }));
  },

  // ---------------------------------------------------------- power save

  /** Chế độ tiết kiệm pin đang bật hay không. */
  isPowerSaveMode() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.isPowerSaveMode();
  },

  // ---------------------------------------------------------- connectivity

  /** Trạng thái mạng hiện tại: { connected, type }. */
  getConnectivityInfo() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getConnectivityInfo().then((s) => parseMaybeJson(s, { connected: false, type: 'none' }));
  },

  // ---------------------------------------------------------- background task (iOS parity)

  /** iOS only — Android trả giá trị giả cho parity API. */
  startBackgroundTask() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.startBackgroundTask();
  },

  endBackgroundTask(taskId) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.endBackgroundTask(taskId || 0);
  },

  // ---------------------------------------------------------- watch position

  /**
   * Theo dõi vị trí foreground (không qua background service).
   * Emit qua onLocation với event='watch'.
   */
  startWatchPosition(options = {}) {
    if (!native) return Promise.reject(new Error('Android only'));
    const opts = Object.assign(
      { interval: 5000, desiredAccuracy: DesiredAccuracy.High, distanceFilter: 0 },
      options
    );
    return native.startWatchPosition(opts);
  },

  stopWatchPosition() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.stopWatchPosition();
  },

  // ---------------------------------------------------------- nhật ký chẩn đoán

  /** Đường dẫn file log ('' nếu chưa bật logToFile). Thường là .../Android/data/<pkg>/files/rnbggeo.log */
  getLogPath() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getLogPath();
  },

  /** Kích thước file log (byte). */
  getLogSize() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getLogSize();
  },

  /** Đọc phần cuối file log, mặc định 200 KB cuối. */
  getLog(maxBytes = 200000) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getLog(maxBytes);
  },

  clearLog() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.clearLog();
  },

  /** Xoá hẳn file log (giống destroyLog của lib gốc). */
  destroyLog() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.destroyLog();
  },

  /**
   * Đẩy file log chẩn đoán lên server: `POST <url>/logs`.
   *
   * Nhờ vậy đọc được log của máy ở xa mà không phải cắm cáp (`adb pull` / Xcode) — đúng
   * nhu cầu khi chẩn đoán các đoạn hành trình bị mất. Cả Android và iOS đều có.
   *
   * @param maxBytes số byte CUỐI của file log gửi đi (mặc định 200 KB, server nhận ≤ 8 MB)
   * @returns {Promise<{ok: boolean, status: number, response: string, bytes: number, logPath: string}>}
   */
  uploadLog(maxBytes = 200000) {
    if (!native) return Promise.reject(new Error('native module không khả dụng'));
    return native
      .uploadLog(maxBytes)
      .then((s) => parseMaybeJson(s, { ok: false, status: 0, response: '', bytes: 0, logPath: '' }));
  },

  /** Mức log 0..5 (0 = tắt). */
  setLogLevel(level) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.setLogLevel(level);
  },

  /** Beep ngắn để debug: 'location' | 'motion' | 'error'. */
  playSound(kind = 'location') {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.playSound(kind);
  },

  // ---------------------------------------------------------- cấu hình / dữ liệu

  /** Toàn bộ cấu hình hiện tại. */
  getConfig() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getConfig().then((s) => parseMaybeJson(s, {}));
  },

  /** Reset SDK rồi áp cấu hình mới (không xoá dữ liệu/geofence). */
  reset(config = {}) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.reset(config).then((s) => parseMaybeJson(s, {}));
  },

  /** Gán giá trị odometer (m). */
  setOdometer(value) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.setOdometer(value);
  },

  /** Chèn một điểm vào DB. Bắt buộc có coords{latitude,longitude}. Trả về uuid. */
  insertLocation(location) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.insertLocation(location);
  },

  /** Xoá một điểm theo uuid. Trả về số bản ghi đã xoá. */
  destroyLocation(uuid) {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.destroyLocation(uuid);
  },

  // ---------------------------------------------------------- quyền & thiết bị

  /** Xin quyền vị trí (fine + background) và activity recognition. */
  async requestPermission() {
    if (!native) return Promise.reject(new Error('Android only'));
    if (Platform.OS !== 'android') return this.getProviderState();
    const { PermissionsAndroid } = require('react-native');
    const perms = [
      PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
      PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION,
    ];
    if (Platform.Version >= 29) perms.push(PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION);
    if (Platform.Version >= 29) perms.push('android.permission.ACTIVITY_RECOGNITION');
    const out = {};
    for (const p of perms) {
      try {
        out[p] = await PermissionsAndroid.request(p);
      } catch (e) {
        out[p] = 'denied';
      }
    }
    return this.getProviderState();
  },

  /** {enabled, gps, network, status, accuracyAuthorization} */
  getProviderState() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getProviderState().then((s) => parseMaybeJson(s, {}));
  },

  /** Thông tin thiết bị. */
  getDeviceInfo() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getDeviceInfo().then((s) => parseMaybeJson(s, {}));
  },

  /** Danh sách cảm biến. */
  getSensors() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getSensors().then((s) => parseMaybeJson(s, []));
  },

  // ---------------------------------------------------------- lịch chạy

  /** {enabled, spec, active} */
  getSchedule() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.getSchedule().then((s) => parseMaybeJson(s, {}));
  },

  startSchedule() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.startSchedule().then((s) => parseMaybeJson(s, {}));
  },

  stopSchedule() {
    if (!native) return Promise.reject(new Error('Android only'));
    return native.stopSchedule().then((s) => parseMaybeJson(s, {}));
  },

  /**
   * Theo dõi vị trí foreground, trả về Subscription có `.remove()`.
   * `watchPosition({interval, distanceFilter}, cb)` — tương thích lib gốc.
   */
  watchPosition(options = {}, cb) {
    if (!native) return { remove() {} };
    const sub = onLocation((loc) => cb && cb(loc));
    this.startWatchPosition(options).catch(() => {});
    return {
      remove: () => {
        sub.remove();
        this.stopWatchPosition().catch(() => {});
      },
    };
  },
};

module.exports = BgGeo;
module.exports.default = BgGeo;
