# Test Report

Date: 2026-10-19

## Tests passed

- `./gradlew :app:testDebugUnitTest` — 9 tests passed:
  - 8 WED Faces API contract tests using MockWebServer (login, album IDs, multipart upload, import path, import counters/failures JSON, refresh, unsupported formats, and HTTP 413).
  - 1 SQLite persistence test confirming server counters and all failed filenames survive closing and reopening the database.
- `./gradlew :app:assembleDebug` — passed. Debug APK generated at `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:lintDebug` — passed with 13 warnings and no errors.

## Issues found and resolved

- The login and token-refresh code treated the raw JSON response string as an already-parsed JSON object. Both now parse the response before reading tokens.
- The import-path test now checks the decoded JSON field instead of relying on the serialized JSON string representation.
- Lint's API 27 navigation-bar theme attribute is now limited to API 27+, and the Android 10–12 Wi-Fi permission declaration includes both fine and coarse location permissions.

## Tests not run / features not verified

- No end-to-end run of Test Mode against a live WED Faces server was performed. API behavior was tested against a local mock server only.
- No live WED Faces login, album selection, upload, or server-side import was performed; no server URL or account was configured for this run.
- No Android emulator or physical device was connected, so installation, touch/layout behavior, Android permission prompts, and device-side test-gallery flow were not exercised.
- Sony camera PTP/IP browsing, thumbnail retrieval, and selected-original transfer were not verified with physical camera hardware.

## Remaining warnings and blockers

- Lint passes with 13 non-blocking warnings: five date-format warnings in the LGPL-2.1 `libptp` source, a version-gated Wi-Fi permission attribute warning, six dependency-update notices, and an Android backup-configuration notice. Third-party PTP source was left unchanged to preserve its licensing and provenance.
- Physical-camera compatibility and live WED Faces API response behavior remain pending real-device/server verification.
- The built APK is a debug-signed development APK, not a release-signed Play Store package.
