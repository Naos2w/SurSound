"""Derive a traceable pre-release version for the same CI run on both platforms."""
import json
import os
import re
from pathlib import Path

root = Path(__file__).resolve().parents[2]
base = (root / "VERSION").read_text(encoding="utf-8").strip()
if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", base):
    raise SystemExit("VERSION must contain a numeric MAJOR.MINOR.PATCH")
run = int(os.environ["GITHUB_RUN_NUMBER"])
attempt = int(os.environ["GITHUB_RUN_ATTEMPT"])
if not 1 <= run <= 999900 or not 1 <= attempt <= 99:
    raise SystemExit("Build number out of range; revise the version policy before continuing")
code = run * 100 + attempt
name = f"{base}-dev.{code}"
ios_build = f"{1 + (run - 1) // 100}.{(run - 1) % 100}.{attempt}"
values = {
    "SURSOUND_BASE_VERSION": base,
    "SURSOUND_BUILD_CODE": str(code),
    "SURSOUND_BUILD_NAME": name,
    "SURSOUND_IOS_BUILD": ios_build,
}
with Path(os.environ["GITHUB_ENV"]).open("a", encoding="utf-8") as out:
    for key, value in values.items():
        out.write(f"{key}={value}\n")
metadata = {
    "version": name,
    "androidVersionCode": code,
    "iosMarketingVersion": base,
    "iosBundleVersion": ios_build,
    "ciRunNumber": run,
    "ciRunAttempt": attempt,
    "ciRunId": os.environ["GITHUB_RUN_ID"],
    "commit": os.environ["GITHUB_SHA"],
    "ref": os.environ["GITHUB_REF"],
}
(Path(os.environ["RUNNER_TEMP"]) / "BUILD-INFO.json").write_text(
    json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
)
print(f"Test build: {name}; Android {code}; iOS {base} ({ios_build})")
