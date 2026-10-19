# WED Faces Camera Operator Android App

Native Android application for browsing supported Sony camera images, choosing originals, and sending them to a WED Faces album.

## Run & Operate

- `pnpm --filter @workspace/api-server run dev` — run the API server (port 5000)
- `pnpm run typecheck` — full typecheck across all packages
- `pnpm run build` — typecheck + build all packages
- `pnpm --filter @workspace/api-spec run codegen` — regenerate API hooks and Zod schemas from the OpenAPI spec
- `pnpm --filter @workspace/db run push` — push DB schema changes (dev only)
- Required env: `DATABASE_URL` — Postgres connection string
- Android source/build: `native-android/README.md`; use JDK 17, Android SDK 35, then `cd native-android && ./gradlew :app:assembleDebug`
- Android unit tests: `cd native-android && ./gradlew :app:testDebugUnitTest`
- The existing workspace API Server is not the WED Faces production backend; configure the target WED Faces URL in the Android app.

## Stack

- pnpm workspaces, Node.js 24, TypeScript 5.9
- API: Express 5
- DB: PostgreSQL + Drizzle ORM
- Validation: Zod (`zod/v4`), `drizzle-zod`
- API codegen: Orval (from OpenAPI spec)
- Build: esbuild (CJS bundle)
- Native app: Kotlin, Jetpack Compose, Material 3, AndroidX WorkManager, SQLite, OkHttp, Sony Wi-Fi PTP/IP

## Where things live

- `native-android/app/src/main/java/com/wedfaces/cameraoperator/` — Compose UI, app state, background transfer worker
- `native-android/app/src/main/java/com/wedfaces/cameraoperator/network/` — WED Faces REST request contract
- `native-android/app/src/main/java/com/wedfaces/cameraoperator/camera/` — Android network selection and Sony PTP/IP adapter
- `native-android/app/src/main/java/com/wedfaces/cameraoperator/data/` — encrypted session tokens, settings, persistent transfer queue
- `native-android/licenses/` — LGPL-2.1 and upstream source attribution/modification notes

## Architecture decisions

- Keep the Android client native Kotlin/Compose; do not replace it with Expo or a web app.
- Use Android `Network`-bound sockets for Sony PTP/IP so the camera Wi-Fi does not capture WED Faces internet traffic.
- PTP browsing must request metadata/thumbnails only; retrieve camera originals only after explicit selection.
- Use the backend album `id` and stable per-upload UUIDs; never substitute `publicId` or fabricate a successful server response.

## Product

The Android app has Camera, Queue, and Settings destinations. It supports Sony Wi-Fi PTP/IP browsing, selected-only original retrieval, persistent uploads/import status, secure WED Faces sign-in, and a clearly labeled test mode using actual Android-picked images.

## User preferences

The requested deliverable is a native Kotlin Android app, not React Native, Expo, or a web app.

## Gotchas

- Physical camera compatibility and live WED Faces API behavior must be verified on the actual target hardware/server; neither is available in this workspace.
- `native-android/README.md` documents the tested route contract, build steps, camera setup, and licensing.

## Pointers

- See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details
