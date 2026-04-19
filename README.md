# Remoty

Share your phone's camera and NFC with another Android device.

## The Problem

You have an Android device without a camera or NFC reader, and your phone has both. Remoty bridges them — your phone's camera appears as a **real system camera** on the other device, and NFC tags are relayed transparently.

## How It Works

```
Phone (Provider)                    Target Device (Consumer)
┌─────────────────────┐             ┌──────────────────────────┐
│  Remoty App         │             │  Remoty App + Shizuku    │
│                     │             │                          │
│  • Camera capture   │◄──WiFi────►│  • VirtualDeviceManager  │
│    (CameraX)        │  encrypted  │  • VirtualCamera         │
│  • H.264 encoding   │  TCP/AES   │  • H.264 decoding        │
│  • NFC reader       │            │  • HostApduService (NFC) │
└─────────────────────┘             └──────────────────────────┘
```

### Camera Pipeline

**Provider:** CameraX capture → YUV_420_888 → H.264 (MediaCodec) → encrypted frames → WiFi

**Consumer:** WiFi → decrypt → H.264 decode (MediaCodec) → Surface → VirtualCamera (Android 15 API)

The VirtualCamera registers with the system's CameraManager. **All apps** — Google Meet, WhatsApp, QR scanners — see it as a real camera.

### NFC Relay

APDU commands are forwarded between the consumer's HostApduService and the provider's physical NFC reader over the encrypted channel.

## Requirements

- **Provider device:** Android 9+ (API 28), camera, NFC
- **Consumer device:** Android 15+ (API 35), [Shizuku](https://shizuku.rikka.app/) installed
- Both devices on the same WiFi network (or WiFi Direct)

### Why Shizuku?

Android's `VirtualCamera` API requires `CREATE_VIRTUAL_DEVICE` permission, which is restricted to system apps. Shizuku grants this via an ADB-activated daemon — no root needed.

## Pairing

1. Open Remoty on both devices
2. Select **Provider** on the phone, **Consumer** on the target
3. Provider shows a 6-digit code
4. Consumer enters the code
5. Devices pair over BLE, then upgrade to encrypted WiFi

Re-pairing is automatic for previously paired devices.

## Security

- **ECDH key exchange** (secp256r1) during pairing
- **AES-256-GCM** encryption on all traffic
- 6-digit pairing code used as additional KDF salt
- No data leaves the local network

## Project Structure

```
app/src/main/kotlin/dev/remoty/
├── camera/
│   ├── CameraCapture.kt          # CameraX capture + YUV frame extraction
│   ├── H264Encoder.kt            # Hardware H.264 encoder (MediaCodec)
│   ├── H264Decoder.kt            # Hardware H.264 decoder (MediaCodec)
│   ├── CameraStreamSender.kt     # Sends encoded frames over channel
│   ├── CameraSessionManager.kt   # Orchestrates full pipeline for both roles
│   ├── CameraPreviewView.kt      # Compose SurfaceView for preview
│   └── VirtualCameraManager.kt   # Shizuku + VirtualDeviceManager
├── ble/
│   ├── BleConstants.kt           # Service UUIDs, magic bytes
│   ├── BleDiscoveryManager.kt    # BLE advertising + scanning
│   └── BlePairingServer.kt       # GATT server for pairing handshake
├── crypto/
│   └── SessionCrypto.kt          # ECDH + AES-256-GCM
├── data/
│   ├── Models.kt                 # DeviceRole, PairedDevice, ConnectionState
│   ├── Protocol.kt               # Wire protocol (all packet types)
│   └── RemotyPreferences.kt      # DataStore persistence
├── net/
│   └── RemotyChannel.kt          # Encrypted TCP channel with framing
├── nfc/
│   └── RelayHostApduService.kt   # HCE service for NFC APDU relay
├── service/
│   └── StreamingService.kt       # Foreground service for background streaming
├── shizuku/
│   └── ShizukuHelper.kt          # Shizuku permission management
├── ui/
│   ├── MainActivity.kt           # Navigation host
│   ├── theme/Theme.kt            # Material 3 theme
│   └── screens/
│       ├── RoleSelectionScreen.kt # Provider / Consumer selection
│       ├── PairingScreen.kt      # BLE pairing UI
│       └── ConnectionScreen.kt   # Active session with camera controls
└── RemotyApp.kt                  # Application class
```

## Building

```bash
./gradlew assembleDebug
```

## Tech Stack

- Kotlin, Jetpack Compose, Material 3
- CameraX + Camera2
- MediaCodec (H.264 hardware encode/decode)
- Android BLE API
- VirtualDeviceManager (Android 15)
- Shizuku
- DataStore Preferences
- kotlinx.serialization

## License

TBD
