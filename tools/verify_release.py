"""Independent verification of the release artifacts.

Deliberately does not import the release tooling: it re-derives everything from
the files on disk, so a bug in `ci_release.py` cannot make its own output look
correct.

`dist/*.apk` is not committed (it ships as a release asset), so a fresh checkout
has a manifest with nothing to compare it against. That case is reported as a
skip and exits 0, so this can be wired into `check.cmd` without breaking the
config check on a clone that has never built a release.
"""

import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
failures = []


def check(name, ok, detail=""):
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" -> {detail}" if detail else ""))
    if not ok:
        failures.append(name)


manifest_path = DIST / "update.json"
if not manifest_path.exists():
    print("[skip] dist/update.json not present")
    sys.exit(0)

raw = manifest_path.read_bytes()

print("== dist/update.json ==")
check("no UTF-8 BOM", raw[:3] != b"\xef\xbb\xbf")
check("no CRLF", b"\r\n" not in raw)

manifest = json.loads(raw.decode("utf-8"))
version = manifest["versionName"]
apk = DIST / f"readon-{version}.apk"

if not apk.exists():
    # The manifest is still checked below for internal consistency; only the
    # digest comparison needs the file.
    print(f"  [skip] {apk.name} not staged; digest not compared")
    digest = None
else:
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()

check("sha256 is 64 lowercase hex", bool(re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"])))
if digest is not None:
    check("sha256 matches the APK", manifest["sha256"] == digest,
          manifest["sha256"][:16] + "...")

# versionCode must equal major*10000 + minor*100 + patch, the scheme shared by
# the Gradle build, UpdateManager and ci_release.py.
parts = [int(p) for p in version.split(".")]
expected_code = parts[0] * 10000 + parts[1] * 100 + parts[2]
check("versionCode matches versionName", manifest["versionCode"] == expected_code,
      f"{manifest['versionCode']} vs {expected_code}")
check("apkUrl tag matches version", f"/v{version}/" in manifest["apkUrl"], manifest["apkUrl"])
check("apkUrl names the right file", manifest["apkUrl"].endswith(f"readon-{version}.apk"))
check("notes non-empty", len(manifest["notes"]) > 100, f"{len(manifest['notes'])} chars")
check("notes keep CJK", any("\u4e00" <= c <= "\u9fff" for c in manifest["notes"]))

print()
print("== build.gradle.kts ==")
gradle = (ROOT / "app-project" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
check("default versionName agrees", f'?:"{version}"' in gradle.replace(" ", ""),
      re.search(r'READON_VERSION_NAME"\)\s*\?:\s*"([^"]+)"', gradle).group(1))
check("default versionCode agrees",
      f'?:"{expected_code}"' in gradle.replace(" ", ""),
      re.search(r'READON_VERSION_CODE"\)\s*\?:\s*"([^"]+)"', gradle).group(1))

print()
print("== CHANGELOG.md ==")
changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
check(f"has a [{version}] section", f"## [{version}]" in changelog)
check("Unreleased section still present", "## [Unreleased]" in changelog)
check("compare link for the new version", f"[{version}]:" in changelog)
# The release notes must be exactly what the changelog section contains, or the
# in-app dialog and the GitHub release page would disagree.
section = changelog.split(f"## [{version}]", 1)[1]
section = section.split("\n", 1)[1] if "\n" in section else ""
section = re.split(r"\n## \[", section, maxsplit=1)[0]
section = re.split(r"\n\[[^\]]+\]:", section, maxsplit=1)[0]
check("notes equal the changelog section", section.strip() == manifest["notes"])

print()
if failures:
    print(f"{len(failures)} 项失败: {', '.join(failures)}")
    sys.exit(1)
print("全部通过")
