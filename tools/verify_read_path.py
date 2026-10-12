"""
End-to-end verification of the exact flow the app uses.

Replicates `Http.encodePath` and `Mirror.rawUrl` in Python, then performs the
whole read path against the live repository:

  1. list the library through api.github.com (what listBooks does);
  2. probe routes with a 2 KB range request (what pickMirror does);
  3. download a complete book through the chosen route (what fetchText does);
  4. decode it the way TextCodec does and confirm it is real Chinese prose.

Step 4 is the one that matters: a route can answer 200 with an error page, and a
truncated file can still decode to mojibake. Only decoding and reading the text
proves the reader would show a book.
"""

import json
import re
import urllib.error
import urllib.parse
import urllib.request

OWNER = "nmaych"
REPO = "ReadonBooklibrary"
BRANCH = "main"


def encode_path(path: str) -> str:
    """Mirror of Http.encodePath: per-segment percent-encoding."""
    return "/".join(urllib.parse.quote(seg, safe="") for seg in path.split("/"))


def mirror_url(mirror: str, path: str) -> str:
    direct = (
        f"https://raw.githubusercontent.com/{OWNER}/{REPO}/"
        f"{encode_path(BRANCH)}/{encode_path(path)}"
    )
    enc = encode_path(path)
    b = encode_path(BRANCH)
    return {
        "direct": direct,
        "jsdelivr": f"https://cdn.jsdelivr.net/gh/{OWNER}/{REPO}@{b}/{enc}",
        "ghproxy": f"https://ghproxy.net/{direct}",
        "ghproxycom": f"https://gh-proxy.com/{direct}",
        "ghfast": f"https://ghfast.top/{direct}",
    }[mirror]


def get(url, timeout=60, headers=None, limit=None):
    req = urllib.request.Request(url, headers=headers or {"User-Agent": "Readon-Android"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if limit is None:
            return resp.read()
        chunks, total = [], 0
        while total < limit:
            chunk = resp.read(min(65536, limit - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
        return b"".join(chunks)


# --- 1. list ----------------------------------------------------------------
api = f"https://api.github.com/repos/{OWNER}/{REPO}/git/trees/{encode_path(BRANCH)}?recursive=1"
tree = json.loads(get(api))["tree"]
books = [t for t in tree if t.get("type") == "blob" and t["path"].lower().endswith(".txt")]
books.sort(key=lambda t: t.get("size", 0))
print(f"[1] 列表：{len(books)} 本书，最小 {books[0]['size'] / 1048576:.2f} MB")

# --- 2. probe ---------------------------------------------------------------
sample = books[0]["path"]
print(f"\n[2] 用 2 KB range 探测线路（样本：{sample}）")
alive = []
for m in ("direct", "jsdelivr", "ghproxy", "ghproxycom", "ghfast"):
    url = mirror_url(m, sample)
    try:
        body = get(url, timeout=20, headers={
            "User-Agent": "Readon-Android",
            "Range": "bytes=0-2047",
        })
        ok = bool(body) and b"<html" not in body[:200].lower()
        print(f"    [{'OK ' if ok else 'BAD'}] {m}")
        if ok:
            alive.append(m)
    except Exception as e:
        print(f"    [FAIL] {m}: {type(e).__name__}")

if not alive:
    raise SystemExit("no route works; nothing further to verify")

# --- 3. download a whole book ----------------------------------------------
chosen = alive[0]
print(f"\n[3] 用 {chosen} 完整下载《{sample}》")
raw = get(mirror_url(chosen, sample), timeout=180)
print(f"    收到 {len(raw)} 字节（仓库报告 {books[0]['size']}）")

if len(raw) != books[0]["size"]:
    print("    ! 长度与仓库不一致，可能被截断")

# --- 4. decode the way the app does ----------------------------------------
print("\n[4] 按 TextCodec 的方式解码")


def decode(data: bytes):
    if data[:3] == b"\xef\xbb\xbf":
        return data[3:].decode("utf-8"), "UTF-8 (BOM)"
    for enc, label in (("utf-8", "UTF-8"), ("gb18030", "GBK")):
        try:
            return data.decode(enc), label
        except UnicodeDecodeError:
            pass
    return data.decode("utf-8", errors="replace"), "UTF-8 (有损)"


text, charset = decode(raw)
print(f"    编码判定：{charset}")
print(f"    字符数：{len(text)}")
print(f"    首行：{text.splitlines()[0][:60]!r}")

replacement = text.count("\ufffd")
cjk = len(re.findall(r"[\u4e00-\u9fff]", text))
print(f"    替换字符：{replacement}")
print(f"    中文字符：{cjk}  ({100 * cjk / max(1, len(text)):.0f}%)")

# --- 5. chapter detection, as Chapters.split does --------------------------
headings = re.findall(
    r"^\s{0,6}第\s*[0-9零一二三四五六七八九十百千两]{1,8}\s*[章回节卷篇部集]\s*[^\n]{0,40}$",
    text,
    re.M,
)
print(f"\n[5] 章节标题匹配：{len(headings)} 个")
for h in headings[:3]:
    print(f"    {h.strip()[:50]}")

print("\n=== 结论 ===")
verdict = (
    len(raw) == books[0]["size"]
    and replacement == 0
    and cjk > len(text) * 0.5
)
print("阅读链路可用" if verdict else "阅读链路存在问题，见上")
