# Skiff Go: notes for agents

- Android project in `android/` (Kotlin 1.9.24, Compose BOM 2024.06.00, AGP 8.5.2, JDK 17).
- Build: `./build-android.sh` (debug) or `cd android && ./gradlew compileDebugKotlin`; tests: `cd android && ./gradlew testDebugUnitTest`.
- The firmware's source is the reference: `second_os/skiff` in this folder (a local clone of github.com/illuminus5/skiff, git-ignored) (`web/EDITOR_PROTOCOL.md`,
  `firmware/src/editor*.c`, `core.h`, `seq.c`). Command numbers from 33 on differ from SLOOP's: never send one to a
  firmware that INFO does not identify as Skiff (`Info.isSkiff`).
- One request in flight (`DeviceController.request`): the FM-1 drops a frame that arrives before it answered the last.
- WATCH ends 3 s after the last request: TRANSPORT is polled as the keep-alive; never stop polling because of a busy
  queue.
- Do not change the version or push unless the user asks.
