"""
Run the exact validation that .github/workflows/ci.yml performs on
dist/update.json, so a manifest that would fail CI fails here first.

The workflow embeds this logic as a heredoc; keeping a copy here means it can be
run without pushing, and the two are compared by tools/check_repo_config.py.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
path = ROOT / "dist" / "update.json"

if not path.exists():
    print("::error::dist/update.json is missing; the app fetches it")
    sys.exit(1)

raw = path.read_bytes()

# A byte-order mark makes the file invalid JSON to strict parsers, and it is
# easy to introduce by accident: on Windows, `Set-Content -Encoding UTF8` adds
# one. The app parses this file, so a BOM is a hard failure, not a cosmetic one.
if raw.startswith(b"\xef\xbb\xbf"):
    print(
        "::error::dist/update.json starts with a UTF-8 BOM. "
        "Write it as UTF-8 without a BOM."
    )
    sys.exit(1)

if not raw.startswith(b"{"):
    print(f"::error::dist/update.json does not start with '{{': {raw[:16]!r}")
    sys.exit(1)

try:
    manifest = json.loads(raw.decode("utf-8"))
except (UnicodeDecodeError, json.JSONDecodeError) as e:
    print(f"::error::dist/update.json is not valid UTF-8 JSON: {e}")
    sys.exit(1)

for key in ("versionCode", "versionName", "apkUrl", "sha256"):
    if key not in manifest:
        print(f"::error::dist/update.json has no '{key}'")
        sys.exit(1)

name = manifest["versionName"]
major, minor, patch = (int(p) for p in name.split("."))
expected = major * 10000 + minor * 100 + patch
if manifest["versionCode"] != expected:
    print(
        f"::error::versionCode {manifest['versionCode']} does not match "
        f"{name} (expected {expected})"
    )
    sys.exit(1)

if f"/v{name}/" not in manifest["apkUrl"]:
    print(f"::error::apkUrl does not point at v{name}: {manifest['apkUrl']}")
    sys.exit(1)

sha = manifest["sha256"]
if not re.fullmatch(r"[0-9a-f]{64}", sha or ""):
    print(f"::error::sha256 is not a lowercase hex digest: {sha!r}")
    sys.exit(1)

print(f"manifest OK: {name} ({manifest['versionCode']}) {sha[:12]}...")
