# Spike (throwaway)

Test app for milestone M0 (see `docs/tasks/M0-spike.md`). It is not the product and will not ship. It is a separate Gradle build that reuses `app/sync` for the `Transport` interface.

Build: `./gradlew :app:assembleDebug` (needs `local.properties` with `sdk.dir`, not committed). The debug APK is at `app/build/outputs/apk/debug/`.

Constraint: the installed SDK has Android platform 34, so dependencies are kept at versions that compile against 34.
