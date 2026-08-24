# Project Status — 2026-08-23

## Milestone 0: Foundation

Status: **started**

### Done
- Android native project structure.
- Kotlin + Compose UI shell.
- File picker.
- Wi-Fi Direct permissions.
- Peer discovery.
- Peer connection request.
- Connection state / group-owner address exposure.
- TCP streaming sender and receiver core.
- Binary transfer protocol header v1.
- SHA-256 receive verification.

### Next exact block
1. Add sender/receiver role selection.
2. On Wi-Fi Direct group formation, start server on group owner.
3. Connect client to group owner IP.
4. Resolve selected URI metadata and SHA-256.
5. Stream a real file from phone A to phone B.
6. Persist receiver output through Storage Access Framework.
7. Show bytes, percentage and MB/s.
8. Validate SHA-256 and surface success/failure.
