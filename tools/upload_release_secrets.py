#!/usr/bin/env python3
"""
Upload the release-signing secrets to the repository.

GitHub encrypts action secrets with the repository's public key, and that key is
a **32-byte Curve25519 public key** -- the libsodium `crypto_box_seal` scheme,
not RSA. PyNaCl ships the real libsodium, so this uses `SealedBox` directly
rather than re-deriving X25519 + XSalsa20 + the BLAKE2b nonce by hand: a
hand-rolled version of that is exactly the kind of code that appears to work and
then produces a secret the runner silently cannot decrypt.

Why a script and not a `gh` command: `gh` is not installed here, and the four
values would otherwise have to be pasted by hand from a file that is deliberately
gitignored. Reading them from `keystore.properties` keeps the workflow's secrets
and the local release signing in agreement.

    python tools/upload_release_secrets.py

Requires GH_TOKEN in the environment (a token with `secrets: write`).
"""

import base64
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
API = "https://api.github.com/repos/nmaych/Readon"
KEYSTORE = ROOT / "keystore" / "readon-release.jks"
PROPS = ROOT / "app-project" / "keystore.properties"


def request(path, method="GET", body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "readon-release-secrets",
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main() -> int:
    token = os.environ.get("GH_TOKEN", "").strip()
    if not token:
        print("GH_TOKEN is not set", file=sys.stderr)
        return 1
    if not KEYSTORE.exists():
        print(f"keystore not found at {KEYSTORE}", file=sys.stderr)
        return 1

    # Values the release workflow expects, read from keystore.properties so the
    # two cannot drift.
    props = {}
    for line in PROPS.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()

    secrets = {
        "READON_KEYSTORE_BASE64": base64.b64encode(KEYSTORE.read_bytes()).decode(),
        "READON_KEYSTORE_PASSWORD": props.get("storePassword", ""),
        "READON_KEY_ALIAS": props.get("keyAlias", ""),
        "READON_KEY_PASSWORD": props.get("keyPassword", ""),
    }
    for name, value in secrets.items():
        if not value:
            print(f"missing value for {name}", file=sys.stderr)
            return 1

    status, body = request("/actions/secrets/public-key", token=token)
    if status != 200:
        print(f"public-key fetch failed: {status} {body[:200]}", file=sys.stderr)
        return 1
    key_info = json.loads(body)

    from nacl.public import PublicKey, SealedBox

    raw = base64.b64decode(key_info["key"])
    if len(raw) != 32:
        # A non-32-byte key would mean GitHub changed the scheme; failing loudly
        # beats uploading something the runner cannot open, which shows up only
        # as "secret could not be decrypted" during a release.
        print(f"expected a 32-byte curve25519 key, got {len(raw)}", file=sys.stderr)
        return 1
    box = SealedBox(PublicKey(raw))

    failed = 0
    for name, value in secrets.items():
        encrypted = base64.b64encode(box.encrypt(value.encode())).decode()
        status, body = request(
            f"/actions/secrets/{name}",
            method="PUT",
            body={"encrypted_value": encrypted, "key_id": key_info["key_id"]},
            token=token,
        )
        # 201 created, 204 replaced.
        ok = status in (201, 204)
        if not ok:
            failed += 1
        print(f"{name} -> {status} {'OK' if ok else body[:200]}")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
