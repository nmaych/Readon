@echo off
REM ---------------------------------------------------------------------------
REM  Build Readon.
REM
REM  Uses the toolchain in E:\deepseekworkspace\androidtools (JDK 17 + Android
REM  SDK 34 + Gradle 8.7 + a warmed Gradle cache), so the build works offline.
REM
REM  Usage:
REM      build.cmd                 assembleDebug (default)
REM      build.cmd assembleRelease
REM      build.cmd clean
REM ---------------------------------------------------------------------------
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
set "TC=E:\deepseekworkspace\androidtools"
set "PROJ=%ROOT%app-project"
set "JAVA_HOME=%TC%\jdk\jdk-17.0.20.1+1"
set "ANDROID_HOME=%TC%\sdk"
set "ANDROID_SDK_ROOT=%TC%\sdk"

REM AGP's own state (analytics settings, debug keystore) is kept inside the
REM toolchain because the user profile directory is not writable in this
REM sandbox. Note: AGP 8.5 refuses to start if the deprecated ANDROID_SDK_HOME
REM is set alongside ANDROID_USER_HOME, even with the same value -- only the
REM modern variable is set here.
set "ANDROID_USER_HOME=%TC%\android-home"

if "%~1"=="" (set "TASK=assembleDebug") else (set "TASK=%~1")

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK not found at %JAVA_HOME%
  exit /b 1
)
if not exist "%ANDROID_HOME%\platforms\android-34\android.jar" (
  echo [ERROR] Android SDK 34 not found at %ANDROID_HOME%
  exit /b 1
)

REM Keep Gradle's caches with the toolchain, where the dependencies are warm.
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%TC%\gradle-home"

REM Regenerate local.properties so a stale SDK path cannot break the build.
> "%PROJ%\local.properties" echo sdk.dir=%ANDROID_HOME:\=\\%

set "GRADLE_CMD="
for /d %%d in ("%TC%\gradle-*") do if exist "%%d\bin\gradle.bat" set "GRADLE_CMD=%%d\bin\gradle.bat"
if not defined GRADLE_CMD (
  echo [ERROR] Gradle not found under %TC%
  exit /b 1
)

echo [info] JAVA_HOME   = %JAVA_HOME%
echo [info] ANDROID_HOME= %ANDROID_HOME%
echo [info] GRADLE_USER = %GRADLE_USER_HOME%
echo [info] task        = %TASK%
echo.

call "%GRADLE_CMD%" %TASK% -p "%PROJ%" --no-daemon --console=plain --offline --no-watch-fs

set "CODE=%ERRORLEVEL%"
if "%CODE%"=="0" (
  echo.
  echo [ok] %TASK% succeeded.
  if exist "%PROJ%\app\build\outputs\apk\release\app-release.apk" (
    echo      APK: %PROJ%\app\build\outputs\apk\release\app-release.apk
  )
  if exist "%PROJ%\app\build\outputs\apk\debug\app-debug.apk" (
    echo      APK: %PROJ%\app\build\outputs\apk\debug\app-debug.apk
  )
) else (
  echo.
  echo [FAIL] %TASK% exited with code %CODE%
)
endlocal & exit /b %CODE%
