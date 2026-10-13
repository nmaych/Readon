#!/usr/bin/env python3
"""
Release bookkeeping for Readon.

Does the two things a release has to get exactly right, in one place so they
cannot drift:

  1. Rewrites `dist/update.json` from the artefact that was actually built --
     version code, version name, download URL and the APK's real SHA-256.
  2. Extracts that version's section from `CHANGELOG.md` so the GitHub release
     page and the in-app update dialog say the same thing.

Run locally to preview, or from CI with `--notes-out`.

    python3 tools/ci_release.py --name 1.0.1 --code 10100 \
        --sha "$(sha256sum dist/readon-1.0.1.apk | cut -d' ' -f1)" \
        --repo nmaych/Readon

Why this is a script and not inline shell: the version scheme is duplicated in
the app (`UpdateManager.versionCodeOf`), the Gradle build and CI. A digest that
disagrees with the file makes the app refuse a perfectly good update, and a
versionCode that disagrees makes it offer an update forever. Both are silent
failures, so the logic is kept testable and asserted here.
"""

import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "dist" / "update.json"
CHANGELOG = ROOT / "CHANGELOG.md"


def version_code_of(name: str) -> int:
    """
    `1.2.3` -> `10203`.

    Must stay identical to `UpdateManager.versionCodeOf` and to the scheme
    documented in CHANGELOG.md.
    """
    parts = re.split(r"[.\-+]", name.strip())
    nums = []
    for part in parts:
        digits = re.match(r"\d+", part)
        if digits:
            nums.append(int(digits.group()))
    while len(nums) < 3:
        nums.append(0)
    return nums[0] * 10000 + nums[1] * 100 + nums[2]


def changelog_notes(name: str) -> str:
    """The `## [1.2.3]` section of the changelog, without its heading."""
    if not CHANGELOG.exists():
        return ""
    text = CHANGELOG.read_text(encoding="utf-8")

    marker = f"## [{name}]"
    if marker not in text:
        return ""

    section = text.split(marker, 1)[1]
    # Drop the heading's own line (it may carry a date).
    section = section.split("\n", 1)[1] if "\n" in section else ""
    # Stop at the next version heading.
    section = re.split(r"\n## \[", section, maxsplit=1)[0]
    # Stop at the trailing link-reference block.
    section = re.split(r"\n\[[^\]]+\]:", section, maxsplit=1)[0]
    return section.strip()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--name", required=True, help="version name, e.g. 1.0.1")
    ap.add_argument("--code", type=int, help="version code; derived if omitted")
    ap.add_argument("--sha", help="SHA-256 of the release APK; computed from --apk if omitted")
    ap.add_argument("--repo", default="nmaych/Readon", help="owner/name")
    ap.add_argument("--apk", help="APK path; its digest is used and --sha is verified against it")
    ap.add_argument("--notes-out", help="write release notes here")
    ap.add_argument("--check", action="store_true",
                    help="validate only; do not write the manifest")
    args = ap.parse_args()

    if not args.sha and not args.apk:
        ap.error("provide --sha, --apk, or both")

    name = args.name.lstrip("v")
    code = args.code if args.code is not None else version_code_of(name)

    # The derived code must match what the caller passed, or the tag, the APK
    # metadata and the manifest disagree.
    derived = version_code_of(name)
    if code != derived:
        print(
            f"::error::versionCode {code} does not match {name} "
            f"(expected {derived})",
            file=sys.stderr,
        )
        return 1

    sha = (args.sha or "").strip()
    if args.apk:
        import hashlib
        h = hashlib.sha256()
        with open(args.apk, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        computed = h.hexdigest()
        if sha and sha != computed:
            print(
                f"::error::--sha {sha[:12]}… does not match {args.apk} "
                f"({computed[:12]}…)",
                file=sys.stderr,
            )
            return 1
        sha = computed

    sha = sha.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", sha):
        print(f"::error::not a SHA-256 digest: {sha!r}", file=sys.stderr)
        return 1

    apk_url = (
        f"https://github.com/{args.repo}/releases/download/v{name}/readon-{name}.apk"
    )

    manifest = {}
    if MANIFEST.exists():
        try:
            manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            print(f"::error::dist/update.json is not valid JSON: {e}", file=sys.stderr)
            return 1

    notes = changelog_notes(name)
    if not notes:
        print(
            f"warning: no '## [{name}]' section in CHANGELOG.md; "
            f"keeping the existing notes",
            file=sys.stderr,
        )
        notes = manifest.get("notes", "")

    manifest.update({
        "versionCode": code,
        "versionName": name,
        "apkUrl": apk_url,
        "sha256": sha,
        "notes": notes,
        # A value the maintainer sets by hand for a forced update; never
        # invented here.
        "mandatory": bool(manifest.get("mandatory", False)),
    })

    if args.notes_out:
        pathlib.Path(args.notes_out).write_text(
            notes or f"Readon {name}", encoding="utf-8"
        )
        print(f"notes  -> {args.notes_out}")

    print(f"version: {name} ({code})")
    print(f"sha256 : {sha}")
    print(f"apkUrl : {apk_url}")
    print(f"notes  : {len(notes)} chars")

    if args.check:
        print("check only; manifest not written")
        return 0

    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    # `newline="\n"` is not optional. `write_text` opens in text mode, so on
    # Windows every "\n" would become "\r\n" -- and `tools/check_repo_config.py`
    # rejects CRLF in this file, as does `stamp_manifest.py`, which sets the same
    # flag. Without it, running a release locally produces a manifest that fails
    # the repository's own check.
    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

    # Re-read to prove what was written parses, and that it landed as LF.
    raw = MANIFEST.read_bytes()
    json.loads(raw.decode("utf-8"))
    if b"\r\n" in raw:
        print("::error::wrote CRLF into dist/update.json", file=sys.stderr)
        return 1
    print(f"manifest -> {MANIFEST.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
