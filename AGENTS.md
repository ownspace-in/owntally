# Instructions for AI coding agents and contributors

This project is OwnTally: an Android app for splitting expenses with friends, with no account and no server of our own.

Read in this order before changing anything:
1. `docs/DESIGN.md`: the decisions. Do not change them without the owner's approval.
2. `docs/BUILD_PLAN.md`: the milestones. Work on one milestone at a time, in order. Detailed task lists live in `docs/tasks/`.
3. `design/README.md`: the screens, as images.

Rules:
- Tests first, from the acceptance criteria in the build plan.
- Keep core logic in plain Kotlin with no Android imports.
- Never write your own cryptography. Never commit secrets. Never add analytics or any network call that is not sync.
- If a requirement is unclear or seems wrong, stop and ask in the pull request. Do not guess.
- One milestone per pull request. All tests and lint must pass.

Commands (fill in once the Gradle project exists):
- Test: `./gradlew test`
- Lint: `./gradlew lint`
