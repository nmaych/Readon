## What changed

<!-- A sentence or two. Link the issue if there is one. -->

## Why

<!-- The reasoning, if it is not obvious from the diff. -->

## How it was checked

<!--
  Be specific. "It compiles" and "it works" are different claims, and this
  project has already shipped one release that compiled and could not open a
  single book.
-->

- [ ] `build.cmd assembleDebug` succeeds
- [ ] `python3 tools/ci_release.py --check` passes (if `dist/update.json` changed)
- [ ] Tried on a device or emulator

## Notes for the reviewer

<!--
  Anything that is easy to miss: a behaviour change, a tradeoff, something you
  deliberately did not do.
-->
