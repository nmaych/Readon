"""Stamp dist/update.json with the real APK digest, then validate it.

Hand-editing a hash is how a release ends up advertising a digest that does not
match the file, which the app then treats as a corrupt download. This computes it
from the APK that is actually being shipped and rewrites the manifest, so the two
cannot disagree.
"""

import hashlib
import json
import os
import sys

DIST = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist"))
MANIFEST = os.path.join(DIST, "update.json")


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    # Load the manifest as UTF-8 and fail loudly if it is not valid JSON, rather
    # than writing a broken file back.
    with open(MANIFEST, "r", encoding="utf-8") as fh:
        raw = fh.read()
    try:
        manifest = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"update.json is not valid JSON: {e}")
        return 1

    version = manifest["versionName"]
    apk = os.path.join(DIST, f"readon-{version}.apk")
    if not os.path.exists(apk):
        print(f"APK for {version} not found: {apk}")
        return 1

    digest = sha256_of(apk)
    size = os.path.getsize(apk)
    old = manifest.get("sha256", "")

    manifest["sha256"] = digest
    # Keep the URL in step with the version, since they are easy to forget
    # separately and a wrong URL is a 404 at the worst moment.
    manifest["apkUrl"] = (
        f"https://github.com/nmaych/Readon/releases/download/v{version}/"
        f"readon-{version}.apk"
    )

    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    print(f"APK      : readon-{version}.apk  {size:,} bytes")
    print(f"sha256   : {digest}")
    print(f"was      : {old or '(unset)'}  {'-> updated' if old != digest else '(unchanged)'}")
    print(f"version  : {manifest['versionCode']} / {version}")
    print(f"apkUrl   : {manifest['apkUrl']}")

    # Re-read to prove the file we just wrote parses.
    with open(MANIFEST, "r", encoding="utf-8") as fh:
        check = json.load(fh)
    assert check["sha256"] == digest
    print("\nupdate.json rewritten and re-parsed OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
