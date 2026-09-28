# CaughtLackin

An Android study app that uses your front camera and phone sensors to catch you slacking, then texts your friends about it.

- **Detects:** picking up your phone, leaving the app, talking to someone (head turned, jaw moving), or walking away from your desk.
- **Strikes:** each violation starts a 15 s alarm countdown. Fix it in time, or a random shame message goes to a random squad member (10 min cooldown between texts).
- **Squad:** 3–5 contacts picked through the system contact picker. Messages are editable, and `{reason}` is filled in with what you did.
- **Calibration mode:** tag your real state while CSVs of the features are logged to `Android/data/com.caughtlackin/files/calibration`. Use them to tune `Thresholds` in `Detection.kt`. No texts are sent in this mode.

Uses CameraX and MediaPipe face/pose landmarkers, all on-device. The model files are downloaded at build time.

**Build:** `./gradlew installDebug` (needs camera, SMS and notification permissions).
