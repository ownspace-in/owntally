# Spike (throwaway)

Test app for milestone M0 (see `docs/tasks/M0-spike.md`). It is not the product and will not ship. It is a separate Gradle build that reuses `app/sync` for the `Transport` interface.

Build: `./gradlew :app:assembleDebug` (needs `local.properties` with `sdk.dir`, not committed). The debug APK is at `app/build/outputs/apk/debug/`.

Constraint: the installed SDK has Android platform 34, so dependencies are kept at versions that compile against 34.

Running the tests on phones: see `docs/SPIKE_RESULTS.md`. Role A starts the test and role B answers. Both use the same room name. For a one-phone check there is a Go responder: `go run ./tools/responder -url ws://host:8080/ws -room spike-1` in `relay/ws-relay`, which plays phone B.
