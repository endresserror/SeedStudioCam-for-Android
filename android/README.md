# XIAO Cam Hybrid (Android)

Android app for the hybrid camera firmware. BLE scans/connects to the XIAO and
carries commands such as start, stop, resolution, and JPEG quality. Wi-Fi carries
the JPEG video and microphone audio so media does not contend with BLE's low
notification throughput.

## Camera access point

The camera creates a WPA2 access point:

- SSID: `XIAO-Cam-Video`
- Password: Set your own unique value before building (see setup below).
- Media endpoint: `192.168.4.1:8080` (TCP, XSAV packet framing)

When Android prompts to join the camera Wi-Fi, approve it. That local Wi-Fi has
no internet access; Android keeps cellular internet separate while the app
binds its media socket to the camera network. Bluetooth remains the control
link. A VPN configured to block local-network traffic can prevent this direct
connection; allow LAN connections for the app in the VPN or pause the VPN while
using the camera.

## Image modes and recording

QVGA 320×240 at JPEG quality 16 is the default motion mode. Use QQVGA 160×120
at quality 20 for the highest frame cadence, or cycle to VGA 640×480 at quality
10 when detail matters more than motion. The hybrid firmware runs the camera at
240 MHz, captures at a 20 ms interval, and replaces stale queued frames so a
slow client does not build up preview delay. Actual frame rate depends on scene,
lighting, camera exposure, and Wi-Fi conditions. JPEG quality uses the ESP
camera driver's scale where lower numbers preserve more detail. OV3660 is a
fixed-focus sensor; software cannot autofocus it.

The **全画面表示** button rotates the phone to landscape, hides controls, and fills
the screen with the camera view; tap the picture or press Back to return to portrait.
Tap **録画開始** while a live frame is visible to save video and audio to
`Movies/XIAO Cam` as H.264/AAC MP4. Stop recording with the same button; the app
finalizes the file before reporting that it is saved.

## Build and flash

Before building, replace `REPLACE_WITH_UNIQUE_PASSWORD` with the same unique
WPA2 passphrase (8–63 characters) in `src/hybrid_main.cpp` and
`android/app/src/main/java/com/endresserror/xiaoblecam/HybridActivity.java`.
The public source intentionally contains no working device password. Do not reuse
the placeholder or a password published in a repository.

Install PlatformIO, Android command-line tools, JDK 17, and Android SDK 35.

```powershell
py -m platformio run -e xiao_esp32s3_sense_hybrid
py -m platformio run -e xiao_esp32s3_sense_hybrid -t upload --upload-port COM8
cd android
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **XIAO Cam**, grant Nearby Devices permission, connect over BLE, then
approve the camera Wi-Fi prompt. The app displays resolution, received frame
cadence, and incoming audio block count; phone media volume controls playback.
Wi-Fi requests and stalled media sockets retry with backoff. If Android does not
hand over a manually joined AP, the app probes the current Wi-Fi network for the
camera's local media endpoint before requesting the camera AP again.

The BLE-only build remains available as `xiao_esp32s3_sense_ble`. The original
USB firmware target is unchanged.
