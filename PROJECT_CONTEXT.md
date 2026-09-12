# ScreenLink Pro — Project Context

This file is the reusable project memory for future development sessions. It records the product goal, architecture, major fixes, GitHub workflow, testing notes, and known limitations. Do not store passwords, PATs, Wi-Fi passwords, or other secrets here.

## Product goal

ScreenLink Pro is an Android 11+ local-network screen-sharing app. One phone acts as the Host and shares its display; another phone acts as the Viewer. Pairing is intended to happen through a QR code, with a manual pairing-code fallback. The app also supports optional Wi-Fi/hotspot onboarding, live device playback audio, immersive fullscreen viewing, landscape/portrait synchronization, and remote touch control through Android Accessibility Service.

Repository: https://github.com/amarmuktagacha/ScreenLinkPro
Main branch: `main`
Package: `com.screenlink.pro`
Current project directory used in the sandbox: `/home/ubuntu/ScreenLinkPro-git`

## Main architecture

- `MainActivity.kt`: Compose UI, Host and Viewer flows, QR scanning, permission launchers, immersive Viewer mode, orientation handling, saved hotspot UI, and remote touch forwarding.
- `capture/CaptureService.kt`: MediaProjection foreground service, H.264 surface encoder, VirtualDisplay, playback-audio capture on API 29+, orientation-time video pipeline recreation, service cleanup, and OEM-safe fallbacks.
- `capture/H264Decoder.kt`: Surface-based H.264 decoder with codec-config/keyframe handling and safe lifecycle.
- `capture/PlaybackAudioPlayer.kt`: Viewer-side low-latency PCM AudioTrack playback.
- `network/ScreenServer.kt`: Host TCP server, pairing handshake, video/audio/control transport, keyframe/config caching, and orientation metadata.
- `network/ScreenClient.kt`: Viewer TCP client, video/audio separation, orientation-size callback, and normalized remote-touch sending.
- `control/RemoteControlAccessibilityService.kt`: Host-side tap/swipe execution using Android AccessibilityService after the user explicitly enables it.
- `util/QrPairing.kt`: QR payload generation/parsing and QR bitmap creation.
- `util/WifiConnector.kt`: Android 11+ system-approved Wi-Fi connection request flow.
- `util/SavedWifiStore.kt`: Persistent saved hotspot name/password profiles with save/remove support.
- `util/Pairing.kt`: Pairing code and network utilities.
- `app/src/main/res/drawable/ic_launcher.xml`: Current premium vector launcher icon.

## Important behavior

### Host

1. Host opens Share my screen.
2. Host enters optional Wi-Fi/hotspot name and password, or selects a saved profile.
3. Host can save/remove hotspot profiles.
4. Host displays a QR code containing host IP, port, pairing code, and optional Wi-Fi credentials.
5. Host grants Android MediaProjection permission through the system dialog.
6. CaptureService starts as a foreground media-projection service, creates an H.264 encoder and local server, then optionally captures playback audio.

### Viewer

1. Viewer scans the Host QR code or enters IP and pairing code manually.
2. On Android 11+, the app requests the system-approved Wi-Fi connection when QR contains Wi-Fi details. Android may still require system confirmation; this cannot be silently bypassed.
3. Viewer connects to the Host server.
4. Viewer displays an immersive black fullscreen surface with system bars hidden.
5. Viewer plays received H.264 video and PCM audio.
6. Viewer tap/swipe gestures are normalized and sent to the Host.

### Permissions

- Screen capture: always requires Android MediaProjection user consent.
- Camera: required for QR scanning.
- Wi-Fi permissions: Android-version-dependent nearby Wi-Fi/location permissions are declared and requested as appropriate.
- Notifications: requested where required for foreground-service notification.
- Accessibility: must be explicitly enabled by the user in Android Settings for remote touch control. The app cannot silently enable it.

## Major fixes already implemented

### Startup and crash fixes

- Android-version-aware MediaProjection parcelable extraction.
- Foreground-service startup order hardened for Android 11–14.
- Android 14 media-projection foreground-service permission and type handling.
- Tecno/OEM typed `startForeground` fallback to legacy overload.
- Invalid/null service intents stop cleanly.
- Capture startup failures are caught and logged instead of crashing the app.
- Hardware H.264 encoder preference with capability checks and multiple resolution fallbacks.
- Codec configure/start/input-surface failures are cleaned up safely.
- Duplicate service starts are ignored.

### Black-screen/reconnect fixes

- Codec-config and keyframe flags are preserved end-to-end.
- Latest codec config/keyframe are cached for late or reopened Viewers.
- Frames arriving before SurfaceView/decoder creation are queued.
- Viewer decoder lifecycle is tied to SurfaceView creation/destruction.
- Reopening Viewer restores the decoder from cached config/keyframe data.

### Fullscreen and rotation fixes

- Connected Viewer hides system bars and uses an edge-to-edge black canvas.
- Activity allows orientation changes.
- Host sends display-size metadata when configuration changes.
- Host now recreates the encoder and VirtualDisplay safely during rotation while preserving the network connection.
- Viewer receives the new dimensions, changes requested orientation, and recreates the decoder without requiring a reconnect.
- Portrait/landscape transitions are intended to keep live video running.

### Audio fixes

- Android playback audio capture is attempted on API 29+.
- Media and game playback usages are captured; unknown usage is also requested for compatibility.
- Audio is transported separately from the video queue so video pressure does not discard audio packets.
- Viewer uses a PCM AudioTrack player.
- Audio failure does not terminate screen playback.
- DRM/protected apps may still block audio capture by Android policy.

### Remote control

- Host AccessibilityService executes normalized taps and swipes from Viewer.
- Required `onAccessibilityEvent` callback is implemented.
- User must enable ScreenLink Pro in Accessibility Settings.
- Secure screens, banking apps, lock screens, and DRM surfaces may reject remote gestures.

### UI and icon

- Host screen uses a scrollable professional Compose layout to avoid clipped QR/buttons.
- Saved hotspot profiles support save, select, and remove.
- Current launcher icon is a custom vector with navy base, coral/orange layered ring, connected screens, and wireless signal.

## Recent verified commits

- `300a675` — Add premium ScreenLink Pro vector launcher icon (latest at time this context was written)
- `2cb93b7` — Prioritize live device audio over video queue drops
- `7c230e0` — Recreate video pipeline safely on rotation
- `0b8e443` — Harden Tecno foreground service startup
- `7589f14` — Implement required accessibility event callback
- `d4f35ef` — Add remote touch control through Accessibility Service
- `e753dc3` — Add live device playback audio to screen sharing
- `9b531fa` — Sync viewer orientation with host fullscreen playback

## GitHub Actions

Workflow: `.github/workflows/android-build.yml`
Purpose: Build a debug APK with Gradle and upload it as an Actions artifact.

Recent successful runs:

- Icon build: run `34686812333`
- Audio build: run `34675047875`
- Rotation build: run `34674854228`
- Tecno hardening build: run `34674769919`
- Remote-control build: run `34670908485`

The GitHub token/credentials must never be written to this file or committed. If a PAT was pasted into chat or exposed elsewhere, revoke it and create a replacement with the minimum required permissions.

## Testing checklist

1. Install the same latest APK on Host and Viewer.
2. On Host, grant MediaProjection permission and start sharing.
3. On Viewer, scan QR and connect.
4. Confirm portrait live video.
5. Play a normal, non-DRM video or music source and confirm Viewer sound.
6. Enter video fullscreen landscape on Host and verify Viewer rotates without reconnecting or freezing.
7. Return Host to portrait and verify Viewer returns to portrait.
8. Reopen Viewer and verify cached keyframe restores video.
9. On Tecno KL4, test Start now and Screen Share repeatedly; collect `adb logcat` if a crash remains.
10. For remote touch, enable Accessibility Service on Host and test tap/swipe on a non-secure app.

## Known limitations

- This sandbox does not have a physical Tecno KL4 connected, so real-device `logcat` reproduction has not been performed here.
- Android MediaProjection always requires user consent.
- Android Wi-Fi connection APIs may show unavoidable system confirmation and vary by OEM.
- Android playback capture works only for apps that allow capture and generally requires Android 10/API 29+.
- DRM/protected audio/video and secure surfaces may be black or silent by design.
- GitHub Actions annotation requests may show a 403 because the configured token lacks checks-read permission; a successful build and uploaded artifact are still valid.

## Future development rules

- Preserve Android 11 compatibility unless a feature explicitly requires a newer API.
- Never start MediaProjection capture without validating the consent result and data intent.
- Keep all codec, VirtualDisplay, AudioRecord, AudioTrack, socket, and thread cleanup exception-safe.
- Avoid storing secrets in source, README, or this context file.
- After every source change: validate XML/Kotlin structure, commit, push to `main`, and run the Android Actions build.
- For device-specific crash reports, request or inspect `adb logcat` from the affected phone instead of guessing.
