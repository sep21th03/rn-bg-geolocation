# rn-bg-geolocation

Free background geolocation for React Native — **Android + iOS** — with motion-aware
tracking, geofencing, a SQLite queue and HTTP sync. No license key, no paid tier.

The API mirrors `@transistorsoft/react-native-background-geolocation`
(`ready` / `start` / `stop` / `onLocation` / `onMotionChange` / `onGeofence` …), so most
example code for that SDK works by changing only the import.

- Android: FusedLocationProvider + foreground service + Activity Recognition + SQLite + OkHttp.
- iOS: CLLocationManager + CoreMotion + region monitoring + SQLite + URLSession.

## Install

```bash
npm install rn-bg-geolocation
```

**iOS**

```bash
cd ios && pod install
```

Add to `Info.plist`:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>We record your trips.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>We record your trips in the background.</string>
<key>NSMotionUsageDescription</key>
<string>Used to detect when you start and stop moving.</string>
<key>UIBackgroundModes</key>
<array>
  <string>location</string>
  <string>fetch</string>
</array>
```

**Background Fetch (optional, iOS).** To let iOS wake the app ~15 minutes after it was
suspended, forward the fetch callback in your `AppDelegate`:

```swift
import rn_bg_geolocation

override func application(_ application: UIApplication,
                          didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
  BgGeo.launchedForLocationEvent = launchOptions?[.location] != nil
  // ...your existing setup
}

override func application(_ application: UIApplication,
                          performFetchWithCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
  BgGeo.handleBackgroundFetch(completionHandler)
}
```

**Android** is autolinked — the library manifest merges the required permissions and the
foreground service. No manual steps.

## Quick start

```js
import BackgroundGeolocation from 'rn-bg-geolocation';

await BackgroundGeolocation.ready({
  distanceFilter: 10,
  interval: 5000,
  desiredAccuracy: BackgroundGeolocation.DesiredAccuracy.High,
  stopOnTerminate: false,
  startOnBoot: true,
  url: 'https://your-server.com/locations',
  autoSync: true,
});

await BackgroundGeolocation.start();

BackgroundGeolocation.onLocation((location) => {
  console.log('location', location.coords.latitude, location.coords.longitude);
});
BackgroundGeolocation.onMotionChange((event) => console.log('moving', event.isMoving));
BackgroundGeolocation.onGeofence((event) => console.log('geofence', event.identifier, event.action));
```

The server receives:

```json
{ "location": { "uuid": "...", "timestamp": 1792552293211, "device_id": "...",
  "coords": { "latitude": 21.56, "longitude": 105.85, "speed": 1.2, "accuracy": 8 },
  "odometer": 428, "activity": { "type": "on_foot" }, "battery": { "level": 0.9 } } }
```

A ready-to-run Node.js server + Leaflet map viewer is included in the demo repo
(`geo-server/`, pure Node, no dependencies).

## API

`ready`, `start`, `stop`, `getState`, `getConfig`, `setConfig`, `reset`,
`getCurrentPosition`, `changePace`, `watchPosition`, `startWatchPosition`, `stopWatchPosition`,
`addGeofence`, `addGeofences`, `removeGeofence`, `removeGeofences`, `getGeofences`,
`getGeofence`, `geofenceExists`, `getGeofenceStats`, `getLocations`, `getCount`, `insertLocation`,
`destroyLocation`, `destroyLocations`, `sync`, `getOdometer`, `setOdometer`, `resetOdometer`,
`hasPermission`, `requestPermission`, `getProviderState`, `getDeviceInfo`, `getSensors`,
`isPowerSaveMode`, `getConnectivityInfo`, `startBackgroundTask`, `endBackgroundTask`,
`getSchedule`, `startSchedule`, `stopSchedule`, `playSound`,
`getLogPath`, `getLogSize`, `getLog`, `clearLog`, `destroyLog`, `setLogLevel`.

Events: `onLocation`, `onMotionChange`, `onGeofence`, `onGeofencesChange`, `onHttp`,
`onEnabledChange`, `onActivityChange`, `onConnectivity`, `onPowerSaveChange`,
`onProviderChange`, `onAuthorization`, `onHeartbeat`, `onLocationFilter`, `onSchedule`.

Full option list with JSDoc: [`types.d.ts`](types.d.ts).

## Key options

| Option | Default | Notes |
|---|---|---|
| `distanceFilter` | `10` | Android + iOS (iOS is distance-based only) |
| `interval` / `locationUpdateInterval` | `5000` | Android only |
| `desiredAccuracy` | `high` | `high` / `balanced` / `low` |
| `stopTimeout` | `5` | Minutes of stillness before location services turn off |
| `stationaryRadius` | `150` | Radius used to detect "leaving the spot" (native fence min 100 m) |
| `stopOnTerminate` | `false` | `false` = resume tracking after the OS relaunches the app (iOS registers a fence before termination) |
| `startOnBoot` | `true` | Android: restart after reboot. iOS: resumes on the next wake |
| `pauseInsideGeofence` | `false` | Do not record while standing inside a fence |
| `zeroLossMode` | `false` | Never stop GPS while stationary (battery heavy) |
| `preventSuspend` | `false` | iOS: keep the app alive after GPS off (battery heavy, arrow stays on) |
| `stationaryLowPowerGps` | `false` | iOS: keep a coarse 100 m location while stationary (arrow stays on; faster "left the spot" detection) |
| `backgroundActivitySession` | `false` | iOS 17+: hold a `CLBackgroundActivitySession` so iOS does not suspend the app — **blue pill always visible**. Turn on only when you need a gap-free journey |
| `safetyGeofenceIntervalMinutes` | `5` | iOS: move the "last known position" geofence while moving |
| `backgroundFetchIntervalMinutes` | `15` | iOS: background fetch interval (0 = off) |
| `geofenceProximityRadius` | `1000` | Only fences within this radius are registered with the OS (iOS allows 20 regions) |
| `geofenceNotifications` | `true` | System notification on geofence ENTER/EXIT |
| `insideWatchdogMinutes` | `10` | iOS: while parked inside a fence, take one coarse sample every N minutes |
| `providerSilenceMinutes` | `3` | Android: no samples while moving → re-arm the provider and log a `gap` diag |
| `elasticityMultiplier` | `1` | Android: distanceFilter follows speed |
| `autoSync` / `batchSync` / `maxBatchSize` | `true` / `false` / `500` | HTTP queue |
| `filter.policy` | `adjust` | `adjust` = Kalman + spike rejection, `passThrough` |
| `logToFile` / `postDiagEvents` | `false` / `false` | `rnbggeo.log` + `<url>/diag` |

## iOS background behaviour

- After `stopTimeout` minutes of stillness the library turns location services off (the
  status-bar arrow disappears), registers a **geofence around the last known position** and
  keeps significant-location monitoring. Leaving that geofence wakes the app and tracking
  resumes — the same model as `react-native-background-geolocation`.
- That fence is re-centred whenever tracking starts, while moving every
  `safetyGeofenceIntervalMinutes`, **and immediately when the app leaves the foreground or is
  about to terminate** — so a region always exists where the device actually was.
- iOS may relaunch a terminated app when a location event arrives; with
  `stopOnTerminate: false` the library resumes tracking even before JS runs. Measured on a
  real device: iOS relaunched the app 10 times within 3 hours after it had been force-quit.
- Apple grants *continuous* background location only when `startUpdatingLocation()` was called
  while the app was in the **foreground**. An app that is woken *into* the background gets a
  short run and is suspended again — so a gap-free journey needs the app to be opened once at
  the start of the trip. `backgroundActivitySession: true` (iOS 17+) removes that requirement,
  at the cost of a permanently visible blue pill.
- The status-bar location arrow is shown by iOS whenever location services are active and
  cannot be hidden by an app. `showsBackgroundLocationIndicator` (default `false`) only
  controls the blue background banner.

## Attendance / check-in use case

Geofence ENTER/EXIT is evaluated by the library on **every GPS sample** (real radius — circle
or polygon), not by the coarse native region: the native region is only the wake-up alarm.
Recommended "battery first, zone check-in only" configuration:

```js
await BackgroundGeolocation.ready({
  stopOnTerminate: false,           // keep tracking alive across OS relaunches
  startOnBoot: true,
  stationaryLowPowerGps: false,     // location services OFF while stationary (no arrow, no drain)
  backgroundActivitySession: false, // no blue pill
  zeroLossMode: false,
  pauseInsideGeofence: true,        // no points while standing inside a fence
  geofenceNotifications: true,
  distanceFilter: 10,
  url: 'https://your-server.com/locations',
});
```

Accuracy you can expect (measured on a real device, iPhone 11 Pro):

| Event | Accuracy | Why |
|---|---|---|
| **ENTER** (arriving) | **~1–2 s** | the device is moving, so the app is already awake and samples every 10 m |
| **EXIT** (leaving) | **~200 m late** | the app is asleep while parked and iOS only wakes it after ~200 m of movement. Set `stationaryLowPowerGps: true` to trade battery for ~100 m detection |

Every location and geofence event is written to SQLite first and uploaded when the network is
available, so a check-in is never lost while offline.

## Requirements

- React Native >= 0.71 (tested on 0.77, new architecture via interop).
- iOS >= 15.1. Android minSdk 24.
- Node >= 18 for the demo server.

## License

MIT © sep21th

