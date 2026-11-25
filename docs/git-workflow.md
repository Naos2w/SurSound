# Git workflow

## Branches

- main: stable released baseline.
- develop: integration baseline.
- feature/*, fix/*, refactor/*, chore/*: branch from develop, PR into develop, squash merge.
- release/*: branch from develop, validate and PR into main with a merge commit; synchronize release changes back to develop through a PR.
- hotfix/*: branch from main, PR into main; synchronize to develop through a PR.

Permanent branches main and develop must never be deleted or force-pushed. Release branches require PR/CI and cannot be force-pushed. Delete temporary branches after merge.

## Issue-driven delivery

- GitHub Issues and native sub-issues are the single source of truth for feature scope, acceptance criteria, status and evidence. [SurSound Development](https://github.com/users/Naos2w/projects/2) is a view of those issues, not a second backlog. Use repository Issues and Milestones when Projects is unavailable. Synchronize project Status with issue state and `status:*` labels: completed/closed → Done; active/review/testing → In Progress; backlog/todo/blocked → Todo. Testing denotes the acceptance stage, not evidence that a test is currently running.
- Assign each task/feature an appropriate milestone, platform, priority and exactly one `status:*` label: `backlog`, `todo`, `in-progress`, `review`, `testing`, `blocked` or `done`.
- Start from the latest `develop`; create one bounded feature/fix/chore branch and PR per task or coherent feature. Include its issue number in the PR body, not a growing multi-feature PR. Record implementation/CI evidence, remaining acceptance and blockers in the issue.
- Require successful `bootstrap`, `android-build` and `ios-receiver` checks and resolved review conversations. Respect branch protection and authorized merge scope. After an authorized squash merge, sync local `develop` before starting the next independent feature; delete only the temporary merged branch when safe.
- A successful merge may complete an implementation or CI sub-issue. Keep device acceptance and parent features open until their actual criteria are met; record measured results, then mark the task `status:done` and close it. PRs into non-default `develop` do not automatically close issues via closing keywords, so update the relevant issues explicitly.
- Do not publish releases or promote to `main` without explicit release authorization and required acceptance.

## Protection

Target active GitHub rulesets: main and develop require PR, passing actual registered required checks, resolved review conversations, no force push, no deletion, no bypass. release/* requires PR, CI, resolved conversations and no force push; deletion after merge is allowed. Initially 0 required approving reviews because this is a single-owner repository. Add independent reviewers when available.

Required checks are added only after the named CI check has actually completed successfully. Do not claim active enforcement if GitHub plan or permissions prevent it. Verify remote rulesets, not merely local policy. Use no direct push after the initial repository bootstrap.

## Releases

Conventional Commits (feat, fix, docs, refactor, test, chore); SemVer tags vMAJOR.MINOR.PATCH. No release tags until acceptance is achieved. Main/release promotion requires recorded real-device acceptance for the applicable scope and explicit release authorization.

- v0.1.0: capture PoC
- v0.2.0: LAN transmission
- v0.3.0: cross-platform interconnection
- v0.4.0: synchronization
- v0.5.0: stereo
- v0.6.0: surround
- v0.7.0: calibration
- v0.9.0: beta
- v1.0.0: store release

These are intended milestones, not completed releases.

### Versioned development packages

- `VERSION` is the target milestone's numeric version (currently `0.4.0`), not evidence of a completed release. CI uses `<VERSION>-dev.<buildCode>`, where buildCode = 100 × the CI workflow run number + run attempt (1–99). Each newer run or rerun within that run has a distinguishable build number. A rerun of an older workflow run is not necessarily newer than a later workflow run.
- Android embeds the same development version as versionName and buildCode as versionCode; locally built unsigned/debug variants without CI metadata use `<VERSION>-dev.local` / code 1 and are not distributed as versioned CI artifacts.
- iOS embeds numeric marketing version and a three-component numeric CFBundleVersion derived from the same run/attempt. The full app, extension and separate device-test target receive identical CI version settings. The local Xcode project fallback is development-only and should be kept aligned with VERSION when moving to a new milestone.
- File names: `SurSound-v0.4.0-dev.<buildCode>.apk` and `SurSound-v0.4.0-dev.<buildCode>-device-test-unsigned.ipa`. CI artifact group names remain stable; the extracted packages are accompanied by BUILD-INFO.json (commit and version metadata) and SHA256SUMS.txt. Do not relabel an old build with an untrue embedded version.
- For Android physical testing, transfer the versioned APK only, verify the checksum, and let the owner install manually. Do not force install/downgrade or uninstall to work around signatures. A debug signing key from a fresh runner can differ; version numbers alone do not guarantee in-place upgrades.
- Stable tags and store releases require separate authorization and acceptance. A development version is not a release.

## CI

Bootstrap CI verifies project structure and repository hygiene only. Add actual lint, typecheck, unit tests, Android build, iOS build, audio integration and security checks as implementation exists. Do not substitute placeholder checks for functional validation.
