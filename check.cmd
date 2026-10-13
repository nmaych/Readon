@echo off
REM ---------------------------------------------------------------------------
REM  Check the repository configuration before pushing.
REM
REM  Runs the checks that catch the mistakes which are invisible locally and only
REM  show up as a red X on GitHub:
REM
REM    * do the workflows parse, and do they have triggers and jobs
REM    * do versionName, versionCode and dist/update.json agree
REM    * is there a CHANGELOG section for the version being shipped
REM    * is signing material excluded from git, and the wrapper jar included
REM    * do the book's encoding detection, chapter splitting, pagination and
REM      shelf labels still behave
REM    * do dist/update.json and the staged APK agree
REM
REM  Usage:
REM      check.cmd
REM
REM  Flow is goto-based rather than nested if-blocks on purpose: %ERRORLEVEL%
REM  inside a parenthesised block is expanded when the block is *parsed*, not when
REM  it runs, so the usual `if ... ( set "X=%ERRORLEVEL%" )` silently captures the
REM  wrong value -- and an empty one makes the final `exit /b` return 0, turning a
REM  failed check into a passing one.
REM ---------------------------------------------------------------------------
setlocal

set "ROOT=%~dp0"
set "PY=%ROOT%tools\python.cmd"
if not exist "%PY%" (
  echo [ERROR] tools\python.cmd not found
  exit /b 1
)

REM The workflow checker needs Node and js-yaml. Both ship with the DSH runtime
REM rather than with the Android toolchain, so they are resolved here.
if not defined READON_NODE set "READON_NODE=C:\Users\Administrator\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe"
if not defined READON_JS_YAML set "READON_JS_YAML=E:\deepseekworkspace\.pnpm-store\v11\projects\0ddadf5306c0400a482a958057c7b69b\asar-extract\dsh\node_modules\js-yaml"

set "CODE=0"

echo [info] checking repository configuration...
echo.

call "%PY%" "%ROOT%tools\check_repo_config.py"
if errorlevel 1 goto :failed

REM The book logic is pure Kotlin with no Android dependencies, so it can be
REM verified on the desktop.
echo.
echo [info] checking book logic ^(encoding, chapters, pagination, shelf labels^)...
call "%ROOT%tools\check-book-logic.cmd"
if errorlevel 1 goto :failed

REM Cross-check dist/ against the files it describes. Skipped when the release APK
REM has not been staged, since a source-only checkout has nothing to compare.
if not exist "%ROOT%dist\update.json" goto :passed
echo.
echo [info] checking release artifacts...
call "%PY%" "%ROOT%tools\verify_release.py"
if errorlevel 1 goto :failed

:passed
echo.
echo [ok] all checks passed.
endlocal & exit /b 0

:failed
set "CODE=%ERRORLEVEL%"
if "%CODE%"=="0" set "CODE=1"
echo.
echo [FAIL] checks failed with exit code %CODE%.
endlocal & exit /b %CODE%
