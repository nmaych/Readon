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

[Unreleased]: https://github.com/nmaych/Readon/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/nmaych/Readon/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/nmaych/Readon/releases/tag/v1.0.0
