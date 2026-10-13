# Changelog

All notable changes to Readon are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versions use [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`versionCode` is derived from the version name as `major*10000 + minor*100 +
patch`, so `1.0.1` is `10001`. The tag, the APK metadata and `dist/update.json`
must all agree on that number -- the app compares `versionCode` and nothing else,
and a mismatch shows up as an update prompt that never goes away.

`tools/ci_release.py` derives and asserts this, so it is checked on every release
rather than trusted.

## [Unreleased]

## [1.0.3] - 2026-10-13

### Fixed

- **打开任何一本书都闪退（显示「正在排版…」后应用关闭）。** `Paginator` produced
  its first page by asking an **empty** list for its last entry -- `pages.last()`
  on a list nothing had been added to yet -- so the very first page of every book
  threw `NoSuchElementException`. The call sits inside `remember { ... }` in the
  reader's composition, so the exception was not caught by the loader's
  `try`/`catch` (which wraps the *loading*, not the layout) and it took the
  process down: the user saw "正在排版…" and then the app closed, for every book
  in the library regardless of size or format. The page list now derives its next
  start offset from `lastOrNull()?.end ?: 0`, so the first page begins at 0
  instead of dereferencing an empty list, and every page is coerced to advance by
  at least one character so a pathological measurer cannot spin.

  The bookkeeping is extracted into `PageCursor`, a class with no Android or
  Compose types, so `tools/check-book-logic.cmd` compiles that exact file and
  pins the regression on the desktop -- a crash on the first page of every book
  is not something to catch by opening a book on a phone.

### Added

- **书架显示阅读章节数与上次阅读时间。** Each shelf card now shows "12/340 章" and a
  relative "上次阅读：今天 14:30" (昨天 / N 天前, then a date past a week). The total
  is recorded by the reader when it builds the outline -- the shelf cannot count
  chapters itself without decoding and scanning the whole book, which is the
  expensive work the reader has already done. Books that have never been opened,
  and records written before this field existed, show nothing rather than a
  fabricated "0/0" or a date of 1970.

  The date arithmetic is in `ShelfLabels`, also Android-free and checked on the
  desktop: "昨天" is a *calendar* day, not 24 hours, so 23:50 and 00:10 twenty
  minutes later are correctly one day apart; a DST transition does not turn a day
  into zero; and a future timestamp (a restored backup, a clock change) reads as
  "今天" instead of a negative day count.

### Changed

- `Paginator` now delegates page bookkeeping to `PageCursor`; the public surface
  the reader uses (`pageCount`, `page`, `pageOf`, `textOf`, `completeAll`) is
  unchanged.

## [1.0.2] - 2026-10-12

### Fixed

- **在线阅读一直显示「正在排版」。** Loading a book buffered the whole file into a
  `ByteArray` and then decoded it, so a 75 MB novel briefly needed two full copies
  plus the decoded `String` -- and every mirror was given a 45-second timeout to
  answer. A blocked route therefore stalled the reader for minutes, and an
  `OutOfMemoryError` during the decode was never caught at all (the reader caught
  `Exception`, and `OutOfMemoryError` is an `Error`), which left the screen on
  "正在排版…" forever. Text books now stream to a cache file and are decoded from
  disk; requests are cancellable and carry whole-call timeouts; the loader reports
  each stage ("正在连接线路…", "正在下载… 42%", "正在排版…"); failures are caught as
  `Throwable`; and the loading screen offers a way back.

- **导入本地 txt 后闪退。** The import read the picked document into memory and
  handed the array to the store, which wrote it to disk -- two full copies of the
  file. The bytes now stream straight from the content provider to a scratch file
  and are renamed into place, so a 64 MB book costs a 64 KB buffer. A failed copy
  also leaves the shelf untouched instead of recording a truncated book.

- **导入书库时间过长。** Reading a book scanned every line by allocating a `String`
  for each one -- half a million allocations for a long novel -- on the path that
  shows "正在排版…". Only lines short enough to be a heading are materialised now.
  Library listing is also no longer repeated needlessly (see below).

- **每次切换到主页都重新获取书库。** The listing was loaded by a `LaunchedEffect`
  inside the home screen, which Compose disposes on every tab switch, so returning
  to 主页 re-ran the whole multi-host listing race. The listing, its error state and
  its downloads now live in an application-scoped holder and are reused until the
  repository, branch, token or pinned route actually changes.

- **无法从书库下载。** Downloads ran on the home screen's coroutine scope, which
  is cancelled the moment the user navigates away -- so starting a download and
  switching to 书架 to watch it arrive killed the transfer. Downloads now run at
  application scope and survive navigation; a truncated transfer is detected and
  reported instead of producing a book that ends mid-sentence; a failed transfer
  cleans up its `.part` file; and the row shows the stage and percentage, or an
  indeterminate ring when the route sends no `Content-Length` (which the proxies
  often do not).

- **检查更新一直显示「连接 GitHub 中」。** The check ran in a `LaunchedEffect`
  scoped to the settings screen, so leaving the tab cancelled it -- and because the
  `lastUpdateCheck` timestamp was only written after it returned, every visit
  restarted it from scratch. It now runs at application scope, only one check can
  be in flight, a failure no longer records the timestamp (so a transient network
  error does not silence retries for six hours), and every route attempt carries a
  15-second ceiling so "no answer" resolves into a message instead of spinning.

- Route probes are cancelled when a winner is found. They were blocking `execute()`
  calls that kept their sockets until their own timeouts expired, so opening a book
  could leave a dozen dead connections draining for a minute.

- The remembered route is probed alone first, so a working network costs one 2 KB
  request per book instead of racing all nine hosts every time.

- 「检测线路」probes its routes concurrently rather than one after another, which on
  a blocked network took nine timeouts and read as a hang.

- The settings screen's 刷新 button only showed a toast; it now actually re-reads
  the library.

- The shelf shows a book's reading progress after the reader is closed over it,
  and a download that finishes while the shelf is open appears immediately.

- Concurrent writes to the library index are serialised. The reader persists a
  position on every page turn while a download or import can finish at the same
  moment, and the unsynchronised read-modify-write could drop a newly imported book.

### Changed

- `BookStore.importLocal` takes an already-copied `File` instead of a `ByteArray`;
  `GithubClient.fetchText` is now `fetchBytes`, used only for EPUB, which genuinely
  needs random access.

### Tooling

- `tools/ci_release.py` writes `dist/update.json` with `newline="\n"`. Python's
  text mode turns every `\n` into `\r\n` on Windows, and `tools/check_repo_config.py`
  rejects CRLF in that file -- so running a release locally produced a manifest that
  failed the repository's own check. The script now also re-reads what it wrote and
  refuses to report success if a CRLF got in.

- `tools/verify_release.py` (new) independently re-derives the release invariants
  from the files on disk -- digest against the staged APK, versionCode arithmetic,
  `apkUrl` filename, and release notes against the CHANGELOG section -- without
  importing the release tooling. Wired into `check.cmd`, which now also propagates
  a failing exit code correctly: `%ERRORLEVEL%` inside a parenthesised block expands
  at parse time, so the previous structure could report failure and still exit 0.

## [1.0.1] - 2026-10-11

### Fixed

- **在线阅读和下载全部失败。** Every request went to
  `raw.githubusercontent.com`, which is fully blocked on some networks (connect
  timeout, then a TLS reset). Listing, reading and downloading all shared that one
  hostname, so the app was unusable end to end.

  Requests now go to several routes at once and take the first success: GitHub
  direct, three jsDelivr nodes, `ghproxy.net`, `gh-proxy.com`, `ghfast.top`,
  `gitmirror` and Statically. A blocked route merely loses the race instead of
  failing the request. Measured against this project's library, `ghproxy.net` and
  `gh-proxy.com` served every file and jsDelivr served only files under 20 MB.

  A route is chosen with a 2 KB `Range` request before any real transfer, so
  picking a route costs about one page of a book rather than retrying a
  multi-megabyte file on nine hosts. The winner is remembered.

- **大文件无法在线阅读。** 33 of the library's 53 books are over 8 MB and the
  largest is 75 MB, while the read cap was 8 MB -- most of the shelf could not be
  opened. The cap is now 64 MB, and books past it are told to download first.

- **下载会耗尽内存。** Downloads streamed through memory, so a 75 MB book had to
  be buffered whole. They now stream straight to disk through a `.part` file that
  is renamed only on success, so an interrupted transfer cannot masquerade as a
  complete book.

- **更新检查误报「已是最新」。** `dist/update.json` lives on
  `raw.githubusercontent.com` too, so the check failed silently on the same
  networks. It now uses the same multi-route fetch.

### Added

- **从本地导入。** The shelf's `+` action imports `txt`, `md`, `html` and `epub`
  files, several at a time. Files are copied into app storage rather than
  referenced by content URI, so a later revocation of the grant cannot make a
  book unreadable. Re-importing the same name updates the record instead of
  duplicating it, and imported books never attempt a network fetch.

- **网络线路设置。** Settings now shows which route is actually in use, allows
  pinning one, and offers a "检测线路" action that tests each route and reports
  which are reachable.

- Download progress is shown as a percentage.

### Changed

- The launcher icon is derived from the supplied artwork instead of being redrawn.
  The adaptive icon's background is sampled from the artwork's own navy field, so
  no launcher mask shape exposes a pale corner.

## [1.0.0] - 2026-10-11

### Added

- 主页: recursive listing of a GitHub library repository, grouped by folder, with
  online reading and one-tap download.
- 书架: generated gradient covers, reading progress, sorting, search and delete.
- 阅读器: paginated text rendering measured by the same engine that draws it,
  chapter detection and a table of contents, in-book search, five reading themes,
  adjustable font size and line spacing, and reading position stored as a
  character offset so it survives a repagination.
- 设置: reading preferences, app theme, library source, storage management and a
  GitHub update check that verifies the APK's SHA-256 before installing.

[Unreleased]: https://github.com/nmaych/Readon/compare/v1.0.3...HEAD
[1.0.3]: https://github.com/nmaych/Readon/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/nmaych/Readon/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/nmaych/Readon/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/nmaych/Readon/releases/tag/v1.0.0
