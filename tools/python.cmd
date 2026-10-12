@echo off
REM ---------------------------------------------------------------------------
REM  Resolve a Python 3 interpreter for the tools in this directory.
REM
REM  Prefers whatever is on PATH, then falls back to the interpreter bundled with
REM  the DSH runtime, which is the one guaranteed to exist on a machine that has
REM  no system Python.
REM
REM  Usage: tools\python.cmd <script.py> [args...]
REM ---------------------------------------------------------------------------
setlocal

set "PY="
for %%c in (python.exe python3.exe py.exe) do (
  if not defined PY (
    for /f "delims=" %%p in ('where %%c 2^>nul') do (
      if not defined PY set "PY=%%p"
    )
  )
)

if not defined PY (
  set "PY=%USERPROFILE%\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
)
if not exist "%PY%" (
  set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
)

if not exist "%PY%" (
  echo [ERROR] no Python 3 interpreter found. 1>&2
  echo [ERROR] Install Python, or set READON_PYTHON to one. 1>&2
  exit /b 1
)

"%PY%" %*
endlocal & exit /b %ERRORLEVEL%
