# Offline Transfer Android

Prototype of a local-first Android file transfer application.

## Goal

Transfer files directly between two Android devices without Internet or cloud storage.

### Planned transport stack

1. Wi-Fi Direct — primary high-speed P2P transport.
2. Local-only hotspot — compatibility fallback.
3. Existing LAN — when both devices already share a local network.
4. Optical QR stream — air-gapped fallback for small payloads.
5. QR / NFC — pairing and session authentication.

## Current milestone — 0.1.0-dev

Implemented:

- Native Android project with Kotlin + Jetpack Compose.
- API 37 / current stable Compose BOM.
- Runtime Wi-Fi Direct permission handling for Android 13+ and older Android versions.
- Wi-Fi Direct peer discovery and connection request UI.
- SAF file picker.
- Transport abstraction.
- Minimal binary transfer header (`OTF1`).
- Streaming TCP sender/receiver primitives.
- SHA-256 integrity verification on receive.
- No full-file buffering in RAM.
- Unit test for protocol header serialization.
- GitHub Actions workflow that runs tests, assembles the debug APK and uploads it as an artifact.

Not wired to UI yet:

- Automatic TCP sender/receiver startup after Wi-Fi Direct group negotiation.
- Destination file selection on receiver.
- Transfer progress screen.
- Resume/reconnect.
- End-to-end encryption.
- QR pairing.
- NFC pairing.

## Toolchain

- Android Gradle Plugin: 9.3.1
- Gradle: 9.5.0
- Kotlin / Compose compiler plugin: 2.3.21
- Compile / target SDK: 37
- Min SDK: 26
- Compose BOM: 2026.08.00

## Build

The repository CI installs Gradle 9.5.0 and Android API 37 directly. Android Studio can import the project using the Gradle settings in the repository.

## First physical-device test

Install the same debug APK on two Android devices. Grant nearby Wi-Fi permission on both. Open the app on both devices and use **Buscar** to verify that each device can discover the other. Connect one device to the other. The next development block wires the resulting group-owner IP into `TcpFileTransfer` and transfers the selected file.

## Security note

The current prototype verifies file integrity with SHA-256 but does **not** yet encrypt transfer payloads. Encryption and authenticated QR/NFC pairing are planned before any production release.
