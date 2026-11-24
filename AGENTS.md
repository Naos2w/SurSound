# SurSound development rules

- Follow docs/requirements.md, docs/architecture.md and docs/git-workflow.md.
- Maintain one authoritative task list: tasks/todo.md. Record actual evidence and blockers; do not mark unfinished work as complete.
- Use personal Git identity Naos2w. Never use company credentials or alter global Git settings.
- Work on feature/*, fix/*, refactor/* or chore/* from develop. No direct pushes, force pushes or deletion of main/develop; changes go through PR and required CI. Release/hotfix rules are in docs/git-workflow.md.
- Do not bypass protections, fake approvals/checks, merge or release without applicable authorization.
- Use the simplest implementation and run relevant tests/builds. One developer by default; no compulsory extra agent reviews.
- Respect Android/iOS user consent, source capture policies, DRM and recording indicators; do not circumvent restrictions.
- Verify hardware-dependent claims with actual devices. Distinguish build results, network tests and acoustic synchronization measurements.
- Keep secrets and signing credentials out of Git. Do not publish captured audio or personal data.
- For new UI design directions obtain approval of a preview before implementation.
