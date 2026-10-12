"""
Probe the raw-file routes against a file that actually exists.

The first version of this probed `README.md`, which this repository does not
have, so every route reported 404 and the result said nothing about whether the
route worked. This one lists the repository first, picks the smallest real book,
and fetches a small byte range so a 40 MB novel is not pulled down to answer a
yes/no question.

Run it to regenerate the route order that ships in Mirror.RACE_ORDER.
"""

import json
import time
import urllib.error
import urllib.parse
import urllib.request

OWNER = "nmaych"
REPO = "ReadonBooklibrary"
BRANCH = "main"
UA = {"User-Agent": "Readon-Android", "Range": "bytes=0-2047"}


def enc(path):
    return "/".join(urllib.parse.quote(seg, safe="") for seg in path.split("/"))


def get(url, timeout=15, headers=None):
    req = urllib.request.Request(url, headers=headers or UA)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            return True, resp.status, body, time.time() - t0
    except urllib.error.HTTPError as e:
        return False, e.code, b"", time.time() - t0
    except Exception as e:
        return False, type(e).__name__, str(e)[:80].encode(), time.time() - t0


# --- 1. list the repository -------------------------------------------------
api = f"https://api.github.com/repos/{OWNER}/{REPO}/git/trees/{enc(BRANCH)}?recursive=1"
ok, status, body, _ = get(api, headers={"User-Agent": "Readon-Android"})
if not ok:
    raise SystemExit(f"cannot list repository: {status}")

tree = json.loads(body)["tree"]
books = sorted(
    (t for t in tree if t.get("type") == "blob" and t["path"].lower().endswith(".txt")),
    key=lambda t: t.get("size", 0),
)

print(f"repository: {len(books)} text books")
print(f"  smallest : {books[0]['path']}  {books[0]['size'] / 1048576:.1f} MB")
print(f"  largest  : {books[-1]['path']}  {books[-1]['size'] / 1048576:.1f} MB")
sizes = [b.get("size", 0) for b in books]
over8 = sum(1 for s in sizes if s > 8 * 1024 * 1024)
over20 = sum(1 for s in sizes if s > 20 * 1024 * 1024)
print(f"  >8 MB    : {over8} of {len(books)}")
print(f"  >20 MB   : {over20} of {len(books)}   (jsDelivr's hard limit)")

# The smallest book, and a large one, both URL-encoded the way the app does.
small = books[0]["path"]
large = books[-1]["path"]
print(f"\nprobing with: {small}")

direct_small = f"https://raw.githubusercontent.com/{OWNER}/{REPO}/{enc(BRANCH)}/{enc(small)}"
direct_large = f"https://raw.githubusercontent.com/{OWNER}/{REPO}/{enc(BRANCH)}/{enc(large)}"


def routes(path, direct):
    e = enc(path)
    b = enc(BRANCH)
    return [
        ("GitHub 直连", direct),
        ("jsDelivr", f"https://cdn.jsdelivr.net/gh/{OWNER}/{REPO}@{b}/{e}"),
        ("jsDelivr Fastly", f"https://fastly.jsdelivr.net/gh/{OWNER}/{REPO}@{b}/{e}"),
        ("jsDelivr Gcore", f"https://gcore.jsdelivr.net/gh/{OWNER}/{REPO}@{b}/{e}"),
        ("Statically", f"https://cdn.statically.io/gh/{OWNER}/{REPO}/{b}/{e}"),
        ("gitmirror", f"https://raw.gitmirror.com/{OWNER}/{REPO}/{b}/{e}"),
        ("ghproxy.net", f"https://ghproxy.net/{direct}"),
        ("ghfast.top", f"https://ghfast.top/{direct}"),
        ("gh-proxy.com", f"https://gh-proxy.com/{direct}"),
        ("ghproxy.cc", f"https://ghproxy.cc/{direct}"),
        ("gh.llkk.cc", f"https://gh.llkk.cc/{direct}"),
        ("hub.gitmirror", f"https://hub.gitmirror.com/{direct}"),
        ("moeyy", f"https://github.moeyy.xyz/{direct}"),
        ("ghp.ci", f"https://ghp.ci/{direct}"),
    ]


def run(title, path, direct):
    print(f"\n=== {title} ===")
    working = []
    for label, url in routes(path, direct):
        ok, status, body, dt = get(url)
        # A range request answered by a proxy may come back 200 (whole file) or
        # 206 (partial). Both mean the route works; only an error means it does
        # not. The payload is checked to be plausible text rather than an HTML
        # error page served with a 200.
        plausible = ok and len(body) > 0 and b"<html" not in body[:200].lower()
        if plausible:
            working.append(label)
            print(f"  [OK  ] {label:20s} {status}  {len(body)}B  {dt:.1f}s")
        else:
            print(f"  [FAIL] {label:20s} {status}  {dt:.1f}s")
    return working


small_ok = run(f"小文件 {small}", small, direct_small)
large_ok = run(f"大文件 {large}", large, direct_large)

print("\n=== 结论 ===")
print("小文件可用:", ", ".join(small_ok) or "（无）")
print("大文件可用:", ", ".join(large_ok) or "（无）")
both = [r for r in small_ok if r in large_ok]
print("两者都可用:", ", ".join(both) or "（无）")
