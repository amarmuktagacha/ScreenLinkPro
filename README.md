# ScreenLink Pro

A clean Android 14-compatible local-network screen-sharing app. One phone acts as the **Host** and streams its display; the second phone acts as the **Viewer**. No cloud account or external server is required.

## Features

- Professional Compose-based interface
- Six-digit pairing code
- Instant QR-code pairing with manual code fallback
- QR can carry Wi-Fi/hotspot SSID and password for automatic system-approved joining
- Local Wi-Fi/hotspot streaming
- Android 11 through current Android versions, including Android 14 media-projection rules
- Android MediaProjection consent flow
- Android 14 media-projection foreground-service support
- H.264 hardware encoding and decoding
- Bounded frame queues to control latency and memory use
- Safe lifecycle cleanup for service, codec, socket, and surface

## Build

Open the project in Android Studio Hedgehog or newer, allow Gradle sync, and run `app` on an Android 7.0+ device. The project targets SDK 34 and uses Java/Kotlin 17.

## Use

1. Install the app on both phones. Android 11 or newer is supported.
2. Connect both phones to the same Wi-Fi network or hotspot.
3. On the first phone choose **Share my screen**, grant capture permission, and copy the six-digit code.
4. On the second phone choose **View another screen** and scan the host QR code. Manual IP/code entry is also available.

When the host QR includes Wi-Fi details, the viewer requests an Android `WifiNetworkSpecifier` connection automatically. Android shows a system confirmation because apps cannot silently change a user's Wi-Fi network. The app requests camera, nearby Wi-Fi (Android 13+), location compatibility (Android 11–12), and notification permissions when it opens.

The QR payload contains the local IP address, TCP port (`47821`), six-digit pairing code, and optional base64-encoded Wi-Fi credentials. The stream is sent directly over the local network; no cloud account or cache is used.

## Notes

The sandbox used for source generation does not contain an Android SDK, so APK compilation could not be executed here. The source tree has been checked for required project files, resource references, and balanced Kotlin structure; Android Studio should be used for the final device build and testing.
