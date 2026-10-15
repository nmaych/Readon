"""Check that the routes this app reads through actually honour `Range`.

Feature 3 (在线阅读时先下载部分文件) rests on one assumption: asking for a byte
range of a book returns that range, not the whole file. If a route answers 200
with the entire body, the app falls back to a full download -- correct, but it
would mean the feature does nothing on that route, and this script is what says
so out loud rather than leaving it to be discovered on a phone.

    python3 tools/check_ranges.py
"""

import json
import re
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request

OWNER = "nmaych"
REPO = "ReadonBooklibrary"
BRANCH = "main"

# The same list as Mirror.RACE_ORDER, and the same URL shapes as Mirror.rawUrl.
MIRRORS = ("direct", "ghproxycom", "ghproxy", "jsdelivr", "fastly", "gcore", "ghfast", "gitmirror", "statically")


def encode_path(path):
    return "/".join(urllib.parse.quote(seg, safe="") for seg in path.split("/"))


def mirror_url(mirror, path):
    enc = encode_path(path)
    b = encode_path(BRANCH)
    direct = f"https://raw.githubusercontent.com/{OWNER}/{REPO}/{b}/{enc}"
    return {
        "direct": direct,
        "jsdelivr": f"https://cdn.jsdelivr.net/gh/{OWNER}/{REPO}@{b}/{enc}",
        "fastly": f"https://fastly.jsdelivr.net/gh/{OWNER}/{REPO}@{b}/{enc}",
        "gcore": f"https://gcore.jsdelivr.net/gh/{OWNER}/{REPO}@{b}/{enc}",
        "ghproxy": f"https://ghproxy.net/{direct}",
        "ghproxycom": f"https://gh-proxy.com/{direct}",
        "ghfast": f"https://ghfast.top/{direct}",
        "gitmirror": f"https://raw.gitmirror.com/{OWNER}/{REPO}/{b}/{enc}",
        "statically": f"https://cdn.statically.io/gh/{OWNER}/{REPO}/{b}/{enc}",
    }[mirror]


def request(url, headers, timeout=20):
    """Return (status, headers, body). A dead route is a result, not an exception.

    The whole point of the mirror list is that most of these hosts do not answer
    on a given network -- that is what `GithubClient.pickMirror` exists for. A
    probe that raised on the first blocked host would report nothing about the
    ones that work.
    """
    req = urllib.request.Request(url, headers={"User-Agent": "Readon-Android", **headers})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()
    except (urllib.error.URLError, socket.timeout, ssl.SSLError, ConnectionError, OSError) as e:
        return None, {}, type(e).__name__.encode()


CONTENT_RANGE = re.compile(r"^bytes\s+(\d+)-(\d+)/(\d+|\*)$", re.I)


def main():
    api = f"https://api.github.com/repos/{OWNER}/{REPO}/git/trees/{encode_path(BRANCH)}?recursive=1"
    status, _, body = request(api, {"Accept": "application/vnd.github+json"})
    if status != 200:
        raise SystemExit(f"listing failed: HTTP {status}")
    tree = json.loads(body)["tree"]
    books = [t for t in tree if t.get("type") == "blob" and t["path"].lower().endswith(".txt")]
    if not books:
        raise SystemExit("no books found")
    books.sort(key=lambda t: t.get("size", 0))
    # The largest book is the one the feature exists for.
    big = books[-1]
    small = books[0]
    print(f"大书 {big['path']} ({big['size'] / 1048576:.1f} MB)")
    print(f"小书 {small['path']} ({small['size'] / 1048576:.2f} MB)\n")

    print(f"{'线路':<14} {'小书 Range':<26} {'大书 Range':<26}")
    print("-" * 68)

    honoured = []
    for mirror in MIRRORS:
        row = []
        for book in (small, big):
            url = mirror_url(mirror, book["path"])
            # Exactly what GithubClient asks for: a 2 KB window from offset 0.
            status, headers, data = request(url, {"Range": "bytes=0-2047"})
            if status is None:
                row.append(f"不通（{data.decode(errors='replace')}）")
                continue
            cr = headers.get("Content-Range") or headers.get("content-range")
            parsed = CONTENT_RANGE.match(cr.strip()) if cr else None
            if status == 206 and parsed:
                total = parsed.group(3)
                row.append(f"206 长度={len(data)} 总={total}")
            elif status == 200:
                row.append(f"200 忽略 Range（收到 {len(data)}）")
            else:
                row.append(f"HTTP {status}")
        print(f"{mirror:<14} {row[0]:<26} {row[1]:<26}")
        if row[0].startswith("206") and row[1].startswith("206"):
            honoured.append(mirror)

    print()
    if honoured:
        print(f"支持 Range 的线路：{', '.join(honoured)}")
        print("这些线路上「先下载部分文件」会真正生效。")
    else:
        print("没有任何线路支持 Range —— 在线阅读会退回整本下载，")
        print("功能 3 在这些线路上不会带来收益（但也不会出错）。")


if __name__ == "__main__":
    main()
