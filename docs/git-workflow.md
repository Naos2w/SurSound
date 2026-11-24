# Git workflow

## Branches

- main: stable released baseline.
- develop: integration baseline.
- feature/*, fix/*, refactor/*, chore/*: branch from develop, PR into develop, squash merge.
- release/*: branch from develop, validate and PR into main with a merge commit; synchronize release changes back to develop through a PR.
- hotfix/*: branch from main, PR into main; synchronize to develop through a PR.

Permanent branches main and develop must never be deleted or force-pushed. Release branches require PR/CI and cannot be force-pushed. Delete temporary branches after merge.

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

## CI

Bootstrap CI verifies project structure and repository hygiene only. Add actual lint, typecheck, unit tests, Android build, iOS build, audio integration and security checks as implementation exists. Do not substitute placeholder checks for functional validation.
