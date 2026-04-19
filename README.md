# Telesor

Share your phone's camera and NFC reader with another Android device over an encrypted WiFi connection.

## The Problem

You have an Android device with no camera and no NFC reader, but your phone has both. You need the camera for video calls, QR scanning, and the NFC reader for passkey scanning on that device.

## The Solution

Telesor creates a **virtual camera** and **NFC relay** on the target device, powered by your phone's real hardware. Apps on the target device see the virtual camera as a real camera — video calls, QR scanners, everything works transparently.

## Architecture

```
Phone (Provider)                    Target Device (Consumer)
┌─────────────────────┐             ┌──────────────────────────┐
│  Telesor App        │             │  Telesor App + Shizuku   │
│                     │             │                          │
│  • CameraX capture  │◄──WiFi────►│  • VirtualDeviceManager  │
│  • H.264 encoding   │  encrypted │  • VirtualCamera (API 35)│
│  • NFC reader       │  AES-256   │  • HostApduService (NFC) │
└─────────────────────┘             └──────────────────────────┘
```

## Features

- 📷 **Camera Streaming** — H.264 low-latency video from phone to target device
- 📱 **Virtual Camera** — Appears as a real camera to all apps (Android 15+, via Shizuku)
- 🔐 **NFC Relay** — APDU forwarding for FIDO2/passkey and ISO-DEP protocols
- 🔒 **End-to-End Encryption** — ECDH key exchange + AES-256-GCM on all traffic
- 📡 **BLE Discovery** — Automatic device discovery, 6-digit pairing code
- 🔄 **Auto-Reconnect** — Exponential backoff reconnection on connection loss
- 🔋 **Battery Optimization** — Foreground service with battery exemption support
- ⚙️ **Settings** — Paired device management, battery controls

## Requirements

### Provider (phone with camera + NFC)
- Android 9+ (API 28)
- Camera
- NFC (for relay feature)
- Bluetooth LE
- WiFi

### Consumer (target device without camera)
- **Android 15+ (API 35)** — Required for VirtualCamera API
- [Shizuku](https://shizuku.rikka.app/) — Required for `CREATE_VIRTUAL_DEVICE` permission
- Bluetooth LE
- WiFi

## Setup

### 1. Install Shizuku on the consumer device

```bash
# Via ADB (one-time setup)
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Or install Shizuku from [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) and start it via wireless debugging.

### 2. Build and install Telesor

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** If the gradle wrapper jar is missing, run `gradle wrapper` first with Gradle 8.11+ installed, or download it from the [Gradle distributions](https://services.gradle.org/distributions/).

### 3. Pair devices

1. Open Telesor on both devices
2. Select **Provider** on the phone, **Consumer** on the target device
3. Grant requested permissions
4. Enter the 6-digit pairing code shown on the provider
5. Devices connect automatically over WiFi

## How It Works

### Pairing Flow
```
Provider                              Consumer
   │  BLE advertise (TLSR magic)  ←──── BLE scan
   │  GATT server ready           ────→ GATT connect
   │                              ←──── Write: "CODE|PUBKEY|DEVICE_ID"
   │  Validate code
   │  Derive session key (ECDH+code)   Derive session key
   │  Read: provider pubkey       ────→
   │  Read: WiFi IP:port          ────→
   │                              ←──── TCP connect (encrypted)
   │  Hello exchange              ←───→ Hello exchange
   ✅ Connected                         ✅ Connected
```

### Camera Pipeline
```
CameraX → YUV_420_888 → I420 → MediaCodec H.264 → AES-256-GCM → TCP
TCP → AES-256-GCM → MediaCodec decode → Surface → VirtualCamera (API 35)
```

### NFC Relay
```
External NFC reader → touches consumer device
→ HostApduService receives APDU
→ Encrypted relay to provider
→ Provider: IsoDep.transceive() on physical tag
→ Response relayed back
→ Consumer returns response to external reader
```

## Project Structure

```
app/src/main/kotlin/dev/telesor/
├── TelesorApp.kt                    # Application class
├── ble/
│   ├── BleConstants.kt             # UUIDs, magic bytes
│   ├── BleDiscoveryManager.kt      # BLE advertising & scanning
│   └── BlePairingServer.kt         # GATT pairing handshake
├── camera/
│   ├── CameraCapture.kt            # CameraX YUV frame capture
│   ├── CameraPreviewView.kt        # Compose SurfaceView wrapper
│   ├── CameraSessionManager.kt     # Full pipeline orchestrator
│   ├── CameraStreamSender.kt       # Encoded frame sender
│   ├── H264Decoder.kt              # Consumer-side MediaCodec decoder
│   ├── H264Encoder.kt              # Provider-side MediaCodec encoder
│   └── VirtualCameraManager.kt     # Android 15 VirtualCamera via Shizuku
├── crypto/
│   └── SessionCrypto.kt            # ECDH + AES-256-GCM
├── data/
│   ├── Models.kt                   # DeviceRole, PairedDevice, etc.
│   ├── Protocol.kt                 # Wire protocol (sealed interface)
│   └── TelesorPreferences.kt       # DataStore persistence
├── net/
│   ├── ConnectionManager.kt        # Connection lifecycle + auto-reconnect
│   └── TelesorChannel.kt           # Encrypted TCP channel
├── nfc/
│   ├── NfcReaderManager.kt         # Provider NFC reader
│   ├── NfcSessionManager.kt        # NFC relay orchestrator
│   └── RelayHostApduService.kt     # Consumer HCE service
├── service/
│   └── StreamingService.kt         # Foreground service
├── shizuku/
│   └── ShizukuHelper.kt            # Shizuku permission management
├── ui/
│   ├── MainActivity.kt             # Navigation + ConnectionManager
│   ├── screens/
│   │   ├── ConnectionScreen.kt     # Active session UI
│   │   ├── PairingScreen.kt        # BLE pairing UI
│   │   ├── PermissionGateScreen.kt # Runtime permission grants
│   │   ├── RoleSelectionScreen.kt  # Provider/Consumer selection
│   │   └── SettingsScreen.kt       # Paired devices, battery, about
│   └── theme/
│       └── Theme.kt                # Material 3 theme
└── util/
    ├── BatteryOptimizationHelper.kt # Battery exemption requests
    └── PermissionHelper.kt          # Runtime permission utilities
```

## Tech Stack

- **Language:** Kotlin 2.1
- **UI:** Jetpack Compose + Material 3
- **Camera:** CameraX
- **Video:** MediaCodec H.264 (hardware)
- **Crypto:** ECDH (secp256r1) + AES-256-GCM
- **Transport:** BLE (discovery) + TCP (data)
- **Virtual Camera:** VirtualDeviceManager (Android 15)
- **NFC:** HostApduService (HCE) + IsoDep
- **Permissions:** Shizuku (for @SystemApi access)
- **Build:** Gradle 8.11 + AGP 8.7

## Security

- All traffic is encrypted with AES-256-GCM
- Session keys are derived from ECDH + 6-digit pairing code (HKDF-like)
- Ephemeral key pairs per session
- Pairing code prevents MITM during key exchange
- No data stored on disk except paired device public keys

## Known Limitations

- **VirtualCamera requires Shizuku** — The `CREATE_VIRTUAL_DEVICE` permission is `@SystemApi` with `signature|privileged` protection. Shizuku (running as shell UID) may or may not be able to grant this depending on the OEM's framework. This needs testing on real devices.
- **NFC relay latency** — APDU round-trip over WiFi adds ~10-50ms. Most protocols tolerate this, but time-critical ones may not.
- **Single camera stream** — Currently supports one camera direction at a time (front or back).

## License

MIT
