@echo off
REM ---------------------------------------------------------------------------
REM  Verify the pure-JVM book logic: encoding detection, chapter splitting, page
REM  bookkeeping, the online-reading cache and the shelf's labels.
REM
REM  These decide whether an imported txt shows real Chinese or a page of
REM  mojibake, how long a book spends on "正在排版", and -- via PageCursor -- whether
REM  opening a book crashes on its first page. They are pure Kotlin with no Android
REM  dependencies, so they can be exercised on the desktop, which matters because
REM  the offline Gradle cache has no JUnit jars, so there is no unit-test task
REM  available.
REM
REM  The app's own sources are compiled directly, never copied: a copy would drift
REM  from what ships, and a test that passes against a stale copy is worse than no
REM  test at all.
REM
REM  Usage:  tools\check-book-logic.cmd
REM ---------------------------------------------------------------------------
setlocal enabledelayedexpansion

set "ROOT=%~dp0.."
set "TC=E:\deepseekworkspace\androidtools"
set "JDK=%TC%\jdk\jdk-17.0.20.1+1"
set "SRC=%ROOT%\app-project\app\src\main\java\com\readon\reader\data"
set "SRC_READER=%ROOT%\app-project\app\src\main\java\com\readon\reader\reader"
set "SRC_UPDATE=%ROOT%\app-project\app\src\main\java\com\readon\reader\update"
set "SRC_NET=%ROOT%\app-project\app\src\main\java\com\readon\reader\net"
set "SRC_UI=%ROOT%\app-project\app\src\main\java\com\readon\reader\ui"
set "CACHE=%TC%\gradle-home\caches\modules-2\files-2.1"

if not exist "%JDK%\bin\java.exe" (
  echo [ERROR] JDK 17 not found at %JDK% 1>&2
  exit /b 1
)

REM Resolve each jar by name anywhere under the Gradle cache. Searching rather
REM than assuming a hash directory: several hashes can exist for one version, and
REM only some of them actually hold the jar.
call :find_jar KOTLIN_HOME "kotlin-compiler-embeddable-1.9.24.jar"
call :find_jar STDLIB      "kotlin-stdlib-1.9.24.jar"
call :find_jar SCRIPT_RT   "kotlin-script-runtime-1.9.24.jar"
call :find_jar ANNOT       "annotations-13.0.jar"
REM Gson, for reading the real dist/update.json back the same way the app does.
REM Hand-unescaping a JSON string literal in the check would be a second
REM implementation of JSON, and the one that is wrong would be the check.
call :find_jar GSON        "gson-2.10.1.jar"

if not defined KOTLIN_HOME (
  echo [ERROR] kotlin-compiler-embeddable-1.9.24.jar not found in the Gradle cache 1>&2
  exit /b 1
)
if not defined STDLIB (
  echo [ERROR] kotlin-stdlib-1.9.24.jar not found in the Gradle cache 1>&2
  exit /b 1
)
if not defined GSON (
  echo [ERROR] gson-2.10.1.jar not found in the Gradle cache 1>&2
  exit /b 1
)

REM trove4j ships with Gradle itself rather than the module cache.
set "TROVE="
for %%f in ("%TC%\gradle-8.7\lib\trove4j-*.jar") do set "TROVE=%%f"
if not defined TROVE (
  echo [ERROR] trove4j not found under %TC%\gradle-8.7\lib 1>&2
  exit /b 1
)

set "OUT=%TEMP%\readon-codec-check"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%" 2>nul

REM The compiler needs its own dependencies on the classpath; the code under test
REM only needs the stdlib.
set "COMPILER_CP=%KOTLIN_HOME%;%STDLIB%;%TROVE%;%ANNOT%;%SCRIPT_RT%"

echo [info] compiling the app's TextCodec, Chapters, PageCursor, TextFlow, ReaderInk, Bookmarks, OnlineCachePolicy, PartialRead, AutoRead, ReaderHud, Markdown, UpdateDecision, ShelfLabels, ShelfGroups, Adapt, HomeMemory and RouteHealth...
REM -no-stdlib/-no-reflect: the compiler is run from a bare jar rather than a Kotlin
REM distribution, so it must not go looking for a "Kotlin home" it does not have.
REM The stdlib is supplied explicitly through -cp instead.
REM
REM PageCursor.kt is compiled from the app tree rather than reproduced here: the
REM crash it fixes (an empty page list asked for its last entry) is only caught by
REM exercising the real code, and Model.kt/ShelfLabels.kt are what the shelf
REM renders. Kotlin only needs the files it is handed, so the Android imports in
REM the surrounding classes never come into it -- which is also why TextFlow.kt,
REM ReaderInk.kt, Bookmarks.kt, OnlineCachePolicy.kt, PartialRead.kt, AutoRead.kt,
REM Markdown.kt, UpdateDecision.kt, Adapt.kt and HomeMemory.kt are kept free of
REM Android and Compose types: that freedom is what lets them be checked here.
"%JDK%\bin\java.exe" -cp "%COMPILER_CP%" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler ^
  -no-stdlib -no-reflect -nowarn -cp "%STDLIB%;%GSON%" -d "%OUT%" ^
  "%SRC%\TextCodec.kt" "%SRC%\Chapters.kt" "%SRC%\Model.kt" "%SRC%\ShelfLabels.kt" ^
  "%SRC%\ShelfGroups.kt" ^
  "%SRC%\ReaderInk.kt" "%SRC%\Bookmarks.kt" "%SRC%\OnlineCachePolicy.kt" ^
  "%SRC%\PartialRead.kt" "%SRC%\Markdown.kt" "%SRC%\HomeMemory.kt" ^
  "%SRC_READER%\PageCursor.kt" "%SRC_READER%\TextFlow.kt" "%SRC_READER%\AutoRead.kt" ^
  "%SRC_READER%\ReaderHud.kt" "%SRC_NET%\RouteHealth.kt" ^
  "%SRC_UPDATE%\UpdateDecision.kt" "%SRC_UI%\Adapt.kt" ^
  "%ROOT%\tools\codec-check\CodecCheck.kt"

if errorlevel 1 (
  echo [FAIL] compilation failed 1>&2
  rmdir /s /q "%OUT%" 2>nul
  exit /b 1
)

echo [info] running checks...
echo.
REM Force UTF-8 so the CJK assertions print readably on a GBK console.
set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8"
REM The repo root is passed explicitly: one group of checks reads the real
REM dist/update.json, and a relative path would depend on where the caller stood.
"%JDK%\bin\java.exe" -Dreadon.repoRoot="%ROOT%" -cp "%OUT%;%STDLIB%;%GSON%" com.readon.reader.data.CodecCheckKt
set "CODE=%ERRORLEVEL%"

rmdir /s /q "%OUT%" 2>nul

if not "%CODE%"=="0" (
  echo.
  echo [FAIL] book logic checks failed 1>&2
) else (
  echo.
  echo [ok] book logic checks passed.
)
endlocal & exit /b %CODE%

REM ---------------------------------------------------------------------------
REM  Set %1 to the full path of the first %2 found under the module cache.
REM ---------------------------------------------------------------------------
:find_jar
set "%~1="
for /f "delims=" %%p in ('dir /s /b "%CACHE%\%~2" 2^>nul') do (
  if not defined %~1 set "%~1=%%p"
)
exit /b 0
