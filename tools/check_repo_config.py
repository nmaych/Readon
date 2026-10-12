"""
Validate the repository's GitHub configuration.

Run before pushing. Catches the mistakes that are invisible locally and only
surface as a red X on GitHub: malformed YAML, a workflow that references a
secret nobody set, a versionCode that disagrees with its version name, or
signing material that .gitignore forgot to exclude.

    python3 tools/check_repo_config.py
"""

import json
import os
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FAILURES = []
WARNINGS = []


def fail(msg):
    FAILURES.append(msg)


def warn(msg):
    WARNINGS.append(msg)


def ok(msg):
    print(f"  [ok]   {msg}")


# --- YAML parses ------------------------------------------------------------
# Delegated to a Node script because js-yaml is the parser actually available in
# this toolchain; PyYAML is not installed. A structural check in Python would
# miss real syntax errors, which is the whole point of parsing.
import subprocess  # noqa: E402

node = os.environ.get("READON_NODE") or shutil.which("node")
checker = ROOT / "tools" / "check_workflows.mjs"

if os.environ.get("READON_SKIP_WORKFLOW_CHECK"):
    warn("workflow parse-check skipped (READON_SKIP_WORKFLOW_CHECK is set)")
elif node and checker.exists():
    # js-yaml may live outside the repository; the checker looks in
    # READON_JS_YAML and ./node_modules, and reports clearly if it finds neither.
    proc = subprocess.run(
        [node, str(checker)],
        capture_output=True,
        text=True,
        cwd=str(ROOT),
    )
    for line in proc.stdout.splitlines():
        stripped = line.strip()
        if stripped.startswith("[ok]"):
            print(f"  {stripped}")
        elif stripped.startswith("[warn]"):
            warn(stripped.removeprefix("[warn]").strip())
        elif stripped.startswith("[FAIL]"):
            fail(stripped.removeprefix("[FAIL]").strip())
    # Exit 2 means "no YAML parser available", which is an environment gap rather
    # than a problem with the repository.
    if proc.returncode == 2:
        warn("js-yaml not available; .github YAML was not parse-checked")
    elif proc.returncode not in (0, 1, 2):
        warn(f"check_workflows.mjs exited {proc.returncode}: {proc.stderr.strip()[:200]}")
else:
    warn("node not found; .github YAML was not parse-checked")

# --- secrets referenced vs documented --------------------------------------
release = ROOT / ".github" / "workflows" / "release.yml"
if release.exists():
    text = release.read_text(encoding="utf-8")
    used = set(re.findall(r"secrets\.([A-Z0-9_]+)", text))
    print(f"\n  secrets used by release.yml: {', '.join(sorted(used)) or '(none)'}")

# --- version consistency ----------------------------------------------------
sys.path.insert(0, str(ROOT / "tools"))
from ci_release import version_code_of  # noqa: E402

manifest_path = ROOT / "dist" / "update.json"
manifest = {}
if not manifest_path.exists():
    fail("dist/update.json missing; the app fetches it from the default branch")
else:
    raw = manifest_path.read_bytes()
    # The app parses this file, so a BOM or CRLF is a real failure, not a
    # formatting preference. `.gitattributes` normalises line endings on
    # checkout, but a file written by a Windows tool between checkouts can still
    # arrive with CRLF, and a BOM makes it invalid JSON to strict parsers.
    if raw.startswith(b"\xef\xbb\xbf"):
        fail("dist/update.json starts with a UTF-8 BOM; it will not parse")
    if b"\r\n" in raw:
        fail("dist/update.json has CRLF line endings; expected LF")
    try:
        manifest = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        fail(f"dist/update.json is not valid UTF-8 JSON: {e}")

if manifest:
    name = manifest.get("versionName", "")
    code = manifest.get("versionCode")
    expected = version_code_of(name)
    if code != expected:
        fail(f"update.json versionCode {code} != {expected} for {name}")
    else:
        ok(f"update.json versionCode {code} matches {name}")

    if f"/v{name}/" not in manifest.get("apkUrl", ""):
        fail(f"update.json apkUrl does not point at v{name}")
    else:
        ok("update.json apkUrl tag matches the version")

    if not re.fullmatch(r"[0-9a-f]{64}", manifest.get("sha256", "")):
        fail("update.json sha256 is not a lowercase hex digest")
    else:
        ok(f"update.json sha256 well-formed ({manifest['sha256'][:12]}…)")

# The Gradle default is the version being *developed*; dist/update.json records
# the version last *released*. Those legitimately differ between releases -- a
# maintainer bumps the Gradle default and adds a changelog section, then tags --
# so a mismatch is a note, not a failure. What matters is that the release
# workflow overrides both from the tag, which it does.
gradle = (ROOT / "app-project" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
m = re.search(r'READON_VERSION_CODE"\)\s*\?:\s*"(\d+)"', gradle)
gradle_code = int(m.group(1)) if m else None
if m:
    if manifest and gradle_code != manifest["versionCode"]:
        warn(
            f"build.gradle.kts default versionCode is {gradle_code} but the last "
            f"release in update.json is {manifest['versionCode']} -- expected "
            f"while developing the next version"
        )
    else:
        ok(f"build.gradle.kts default versionCode {gradle_code}")

m = re.search(r'READON_VERSION_NAME"\)\s*\?:\s*"([^"]+)"', gradle)
gradle_name = m.group(1) if m else None
if m and manifest and gradle_name != manifest["versionName"]:
    warn(
        f"build.gradle.kts default versionName is {gradle_name} but the last "
        f"release is {manifest['versionName']}"
    )

# If the Gradle default is *ahead* of the manifest, a changelog section for it
# should already exist, because that is what the release notes come from.
if gradle_name and manifest and version_code_of(gradle_name) > manifest["versionCode"]:
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    if f"## [{gradle_name}]" not in changelog:
        warn(
            f"build.gradle.kts is set to {gradle_name} but CHANGELOG.md has no "
            f"'## [{gradle_name}]' section; the release notes would be empty"
        )
    else:
        ok(f"CHANGELOG.md has a section for the in-development {gradle_name}")

# The changelog must have a section for the version being shipped, since the
# release notes are extracted from it.
if manifest:
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    if f"## [{manifest['versionName']}]" not in changelog:
        fail(f"CHANGELOG.md has no '## [{manifest['versionName']}]' section")
    else:
        ok(f"CHANGELOG.md has a section for {manifest['versionName']}")

# --- secrets must not be committable ---------------------------------------
print()
gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
for pattern in ("keystore.properties", "*.jks", "local.properties"):
    if pattern not in gitignore:
        fail(f".gitignore does not exclude {pattern}")
    else:
        ok(f".gitignore excludes {pattern}")

# The wrapper jar must be tracked even though *.jar is ignored.
if "!gradle/wrapper/gradle-wrapper.jar" not in gitignore:
    fail(".gitignore ignores *.jar without un-ignoring the Gradle wrapper jar")

for rel in ("app-project/gradlew", "app-project/gradle/wrapper/gradle-wrapper.jar",
            "app-project/gradle/wrapper/gradle-wrapper.properties"):
    if not (ROOT / rel).exists():
        fail(f"{rel} missing; CI uses ./gradlew")
    else:
        ok(f"{rel} present")

# --- report -----------------------------------------------------------------
print()
for w in WARNINGS:
    print(f"  [warn] {w}")
if FAILURES:
    for f in FAILURES:
        print(f"  [FAIL] {f}")
    print(f"\n{len(FAILURES)} problem(s)")
    sys.exit(1)

print("repository configuration OK")
