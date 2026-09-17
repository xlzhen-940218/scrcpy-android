# scrcpy-android (v4.1)

- This application is an Android port of the official desktop application [**Scrcpy**](https://github.com/Genymobile/scrcpy) (upgraded to **v4.1**).
- Mirrors display, audio, multi-touch controls, and clipboard between Android devices over WiFi / ADB.
- Uses direct socket streaming for low-latency hardware video (H.264 / H.265) and audio (RAW PCM / OPUS / AAC) decoding.

## Features (v4.1)
- **Official Scrcpy 4.1 Server**: Directly integrated from official Genymobile scrcpy 4.1 server codebase.
- **Audio Forwarding**: Real-time remote device audio streaming to local device speakers/headphones (RAW PCM, OPUS, AAC).
- **Video Codecs**: Support for both **H.264 (AVC)** and **H.265 (HEVC)** hardware-accelerated video decoding.
- **Max Framerate Limiting**: Choose between Unlimited, 60 FPS, or 30 FPS to optimize network bandwidth.
- **Turn Screen Off**: Mirror with the remote device screen powered off to save battery.
- **Stay Awake**: Prevent the remote device from sleeping while mirroring.
- **Multi-Touch & Gestures**: Full multi-touch support for pinch-to-zoom, gestures, and games.
- **Bidirectional Clipboard Sync**: Seamlessly sync clipboard between the controlling and controlled Android devices.
- **Floating Window Mode**: Run remote screen in a draggable, resizable floating window.

## Instructions to Use

- Ensure both devices are connected to the same local Wi-Fi network.
- Enable **Wireless Debugging / ADB over Network** on the target device to be mirrored.
- Open `scrcpy-android` and enter the target device's IP address.
- Configure your preferred settings:
  - **Resolution & Bitrate** (e.g., 1280x720 @ 4Mbps).
  - **Video Codec** (H.264 or H.265).
  - **Max FPS** (Unlimited, 60, or 30).
  - **Audio Forwarding** (Enable/Disable).
  - **Turn Screen Off** / **Stay Awake**.
- Tap **START**.
- Accept and trust ("Always allow from this computer") the ADB authorization prompt on the target device.
- Screen and audio mirroring will begin automatically!

## Gestures & Controls
- **Double Tap**: Wake up the remote device.
- **Proximity Sensor Covered + Double Tap**: Put the remote device to sleep.
- **Swipe up from bottom edge**: Reveal local system navigation bar.
- **Back / Home / Menu**: Hardware navigation buttons forwarded to remote device.

## Building from Source

Requires Android SDK (API 34+) and Java 17.

```bash
# Build release server jar and client debug APK
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## LICENSE

- Client application is licensed under GPLv3.
- Server component is licensed under Apache License 2.0.

