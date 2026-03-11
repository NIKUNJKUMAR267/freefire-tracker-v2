# FreeFire Live Stats Tracker — Android APK Setup Guide

## Prerequisites
- Android Studio Hedgehog (2023.1) or newer
- JDK 17+
- Android device or emulator with Android 8.0+ (API 26+)

---

## Step 1 — Add google-services.json

Before building, you MUST add your Firebase config file:

1. Go to https://console.firebase.google.com
2. Open (or create) your Firebase project
3. Add Android app with package: `com.livewin.freefiretracker`
4. Download `google-services.json`
5. Place it at: `android-app/app/google-services.json`
   (The placeholder file `google-services.json.PLACEHOLDER` shows the path)

---

## Step 2 — Open in Android Studio

1. Open Android Studio
2. Click **File → Open**
3. Select the `android-app/` folder (this directory)
4. Wait for Gradle sync to complete (downloads ~300MB of dependencies on first run)

---

## Step 3 — Build APK

### Debug APK (for testing)
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (for distribution)
```
Build → Generate Signed Bundle / APK
```
Follow the signing wizard to create or use a keystore.

### Via command line
```bash
cd android-app
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

---

## Step 4 — Device Permissions Setup

When you first run the app on your device:

1. **Overlay Permission** — Tap "Grant" → toggle ON for "FF Stats Tracker"
2. **Notifications** — Tap "Grant" → Allow (Android 13+)
3. **Screen Capture** — Shown automatically when you tap "START TRACKING" → tap "Start now"

---

## Step 5 — How to Use

1. Open the app and grant all permissions
2. Tap **START TRACKING**
3. Grant screen capture when prompted
4. Minimize the app and open Free Fire
5. The floating HUD will appear over the game showing:
   - Kills count (red)
   - Players alive (teal)
   - Current game state
   - Anti-cheat flag (if triggered)
6. Stats are uploaded to Firestore in real-time whenever a matching contest is found

---

## Architecture Overview

```
MainActivity.kt
├── Requests: SYSTEM_ALERT_WINDOW, POST_NOTIFICATIONS, MediaProjection
├── Starts: ScreenCaptureService, OverlayHudService
└── Receives: BroadcastReceiver for live stats display

ScreenCaptureService.kt (Foreground Service)
├── MediaProjection → VirtualDisplay → ImageReader
├── Captures screen every 2 seconds
├── → OcrProcessor.kt (ML Kit text recognition)
│     ├── Kills region (top-right red area, 78-98% width, 2-12% height)
│     ├── Alive region (top-right teal area, 72-92% width, 10-20% height)
│     ├── Room ID region (left 38% of screen, 18-38% height)
│     └── Death detection (center region, 38-62% height)
├── → GameStateMachine.kt
│     IDLE → ROOM_JOIN → PRE_MATCH → IN_MATCH → PLAYER_DEAD → MATCH_ENDED
├── → AntiCheatManager.kt
│     ├── Session watermark uniqueness check
│     └── Alive count monotone validation
├── → FirebaseManager.kt
│     ├── Query: contests WHERE roomId=X AND status="running"
│     └── Write: contests/{id}/liveScores/{playerId}
└── → OverlayHudService.kt
      └── WindowManager floating HUD (draggable)
```

---

## Firestore Data Structure

```
contests/
  {contestId}/
    roomId: "ABC123"        # matched from OCR
    status: "running"       # must be "running" for tracking to activate

    liveScores/
      {playerId}/
        kills: 5
        rank: 12
        playersAlive: 23
        playerDead: false
        cheatFlagged: false
        lastUpdated: 1709123456789
```

---

## OCR Region Tuning

If kills/alive numbers are not being detected correctly, adjust the region ratios
in `OcrProcessor.kt`:

```kotlin
private val KILLS_REGION = floatArrayOf(0.78f, 0.02f, 0.98f, 0.12f)
private val ALIVE_REGION = floatArrayOf(0.72f, 0.10f, 0.92f, 0.20f)
private val ROOM_ID_REGION = floatArrayOf(0.00f, 0.18f, 0.38f, 0.38f)
private val DEATH_REGION   = floatArrayOf(0.25f, 0.38f, 0.75f, 0.62f)
```

Values are `[left, top, right, bottom]` as fractions of screen width/height.

---

## Customizing Player ID

Currently hardcoded as `"player_001"` in `MainActivity.kt`:

```kotlin
private const val DEFAULT_PLAYER_ID = "player_001"
```

Replace this with your auth/login system's player ID for production use.

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Overlay not showing | Grant SYSTEM_ALERT_WINDOW permission in device settings |
| OCR not reading kills | Tune KILLS_REGION ratios in OcrProcessor.kt |
| Firebase not connecting | Check google-services.json is in app/ directory |
| MediaProjection crash | Ensure foreground service type is `mediaProjection` in manifest |
| High battery drain | Increase CAPTURE_INTERVAL_MS in ScreenCaptureService.kt |
| Alive count flagged | OCR noise — increase violation threshold in AntiCheatManager.kt |
