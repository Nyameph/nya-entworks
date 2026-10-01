@echo off
rem ============================================================
rem  nya-entworks - desktop shell, one-click launcher.
rem  Double-click this file. It pre-checks the two things that
rem  actually bite, rebuilds the backend when it is stale, then
rem  hands the window over to electron. No business logic here.
rem
rem  In order:
rem    (0) desktop\java-home.txt?     machine-local JAVA_HOME override
rem                                   (this project needs Java 25 --
rem                                   see the block below).
rem    (1) electron binary present?   npm install's postinstall can
rem                                   fail silently.
rem    (2) a jar exists in ..\target? this script never guesses.
rem    (3) shell already running?     -> skip the rebuild below.
rem        java/config newer than jar? -> mvnw package -DskipTests.
rem    (4) start electron.
rem
rem  On failure: read desktop\sidecar.log, and see the symptom
rem  table (appendix B) in docs\ .
rem
rem  KEEP THIS FILE ASCII-ONLY. Non-ASCII bytes break the cmd.exe
rem  parser on a 936 (GBK) console -- even comments: the UTF-8
rem  bytes get decoded as GBK, eat the following characters, and
rem  a mangled "rem" line turns into "is not recognized as an
rem  internal or external command". chcp does NOT fix parsing,
rem  only output. Proven twice on this machine.
rem ============================================================
title nya-entworks
cd /d "%~dp0"

rem (0) Machine-local JDK. This project compiles and runs on Java 25
rem     (pom.xml sets <java.version>25</java.version>), while the
rem     machine default may still be older. A wrong JAVA_HOME bites
rem     twice, and neither message points at JAVA_HOME:
rem       - the build dies first on --sun-misc-unsafe-memory-access,
rem         a JDK-24+ only flag in .mvn\jvm.config ("Unrecognized
rem         option", so the JVM never even starts);
rem       - if the jar is already fresh the build is skipped and the
rem         sidecar dies later with UnsupportedClassVersionError
rem         (main.js findJava() reads JAVA_HOME too).
rem     java-home.txt holds one line: the JDK root, e.g.
rem       C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot
rem     It is gitignored on purpose -- machine state, same rule as
rem     config\*.yaml; the repo must not carry one machine's paths.
rem     Absent = fall back to the ambient JAVA_HOME, exactly as before.
rem     Both halves below inherit this: mvnw.cmd and the electron
rem     child process (main.js reads process.env.JAVA_HOME).
if exist "%~dp0java-home.txt" set /p JAVA_HOME=<"%~dp0java-home.txt"

rem Code page 65001 (UTF-8). The shell now tees the backend's stdout into this
rem window, and the backend writes UTF-8 (main.js pins -Dstdout.encoding=UTF-8,
rem see tee() there). On the default 936 console every Chinese log line would
rem come out as mojibake. Output only -- it does NOT change how cmd.exe parses
rem this file (that is why the file still has to stay ASCII-only).
rem If a tool below misbehaves under 65001, comment this line out to go back.
chcp 65001 >nul

rem (1) The electron binary must really exist: npm install's postinstall
rem     downloads it separately and can fail silently (it reported
rem     "added 13 packages" while dist\electron.exe was absent).
if not exist "node_modules\electron\dist\electron.exe" (
    echo [shell] missing node_modules\electron\dist\electron.exe
    echo         run in this folder:  npm install
    echo         if that succeeds but the file is still missing, run:
    echo             node node_modules\electron\install.js
    echo.
    pause
    exit /b 1
)

rem (2) There must already be a jar in ..\target. This script only rebuilds
rem     (step 3b); it never does the very first build for you.
dir /b "..\target\*.jar" >nul 2>&1
if errorlevel 1 (
    echo [shell] no jar in ..\target -- run this in the project root first:
    echo             ./mvnw clean package -DskipTests
    echo.
    pause
    exit /b 1
)

rem (3a) Is the shell already running? This check comes FIRST and decides
rem      everything else: rebuilding rewrites the jar in ..\target, and a live
rem      instance holds it open. Skipping also means a second double-click just
rem      raises the existing window instead of fighting over the jar.
rem      Two earlier spellings of this check were wrong and were thrown away:
rem        tasklist | find /i "electron.exe"        - if the script is started
rem          from Git Bash, PATH has Git's Unix find first, the pipe fails and
rem          the guard silently never fires.
rem        tasklist | %SystemRoot%\System32\find.exe - still unreliable here:
rem          it errored with "access denied" and made the guard fire while
rem          NOTHING was running (seen during testing, 2026-09-16).
rem      Get-Process needs no pipe and no external exe, so it cannot be
rem      shadowed by PATH. Name is matched without the .exe suffix.
set RUNNING=
powershell -NoProfile -Command "if(Get-Process -Name electron -ErrorAction SilentlyContinue){exit 1}; exit 0" 2>nul
if errorlevel 1 set RUNNING=1
if defined RUNNING echo [shell] shell already running - skipping the rebuild check.

rem (3b) Only when nothing is running: is the jar stale? Exit code 1 from the
rem      powershell means some java/config file is newer than the newest jar.
rem      Front-end js/css/html is read straight from disk, so those extensions
rem      are filtered out on purpose -- otherwise app.js's mtime alone would
rem      false-alarm forever.
rem      NOTE: never move that powershell line inside an if(...) block; its
rem      parentheses would then have to be escaped.
set STALE=0
if not defined RUNNING powershell -NoProfile -Command "$j=(Get-ChildItem '..\target\*.jar'|Sort-Object LastWriteTime|Select-Object -Last 1); $n=(Get-ChildItem '..\src\main' -Recurse -File|Where-Object{$_.Extension -in '.java','.yaml','.yml','.xml' -and $_.LastWriteTime -gt $j.LastWriteTime}); if($n){Write-Host ('[shell] '+$n.Count+' java/config file(s) newer than the jar') -ForegroundColor Yellow; exit 1}; exit 0" 2>nul
if not defined RUNNING if errorlevel 1 set STALE=1

if "%STALE%"=="1" (
    echo [shell] rebuilding backend first, about 15-20s...
    pushd ..
    rem Use an explicit path, NOT a bare "mvnw.cmd": cmd.exe only searches the
    rem current directory when NoDefaultCurrentDirectoryInExePath is unset, and
    rem some environments (including the one this was tested from) set it --
    rem there a bare name dies with "'mvnw.cmd' is not recognized". pushd stays
    rem so Maven itself still runs with the project root as its working dir.
    call "%~dp0..\mvnw.cmd" package -DskipTests
    if errorlevel 1 (
        popd
        echo [shell] build FAILED - nothing was started. If the message above
        echo         mentions a locked or undeletable jar, an orphan backend may
        echo         be holding it: see desktop\.sidecar.pid. Otherwise see the
        echo         symptom table in docs\ .
        pause
        exit /b 1
    )
    popd
    echo [shell] rebuild done.
)

rem (4) Start the shell. Keep this console open: it shows the [shell] lines AND
rem     the backend's own stdout/stderr (main.js tees them here; a copy still
rem     goes to desktop\sidecar.log, which is now UTF-8).
rem     Quit by closing the app window (x) -- that kills the backend too.
rem     Closing this black console instead is a side path: the backend can
rem     become an orphan, cleaned up on the next start via .sidecar.pid.
echo [shell] starting... the backend connects to MySQL, expect 5-15s.
"node_modules\electron\dist\electron.exe" .
