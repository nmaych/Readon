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
REM
REM  Usage:
REM      check.cmd
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

echo [info] checking repository configuration...
echo.

call "%PY%" "%ROOT%tools\check_repo_config.py"
set "CODE=%ERRORLEVEL%"

echo.
if "%CODE%"=="0" (
  echo [ok] repository configuration is valid.
) else (
  echo [FAIL] %CODE% problem^(s^) found.
)
endlocal & exit /b %CODE%
