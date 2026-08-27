@echo off
REM Arima Notebooks — Windows startup script
REM Exit code 42 from the JVM means "restart requested"; this script loops automatically.

setlocal enabledelayedexpansion

cd /d "%~dp0\.."

echo =======================================
echo   Arima Notebooks v1.0
echo   Java ^| JavaScript ^| C# ^| F# ^| JShell
echo =======================================
echo.

REM ── Dependency checks ──────────────────────────────────────────────────────
java -version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo ERROR: Java not found. Please install JDK 17 or higher.
    pause
    exit /b 1
)
echo Java:
java -version 2>&1 | findstr /C:"version"

node --version >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo Node.js: (JavaScript cells enabled^)
    node --version
) else (
    echo Node.js: not found -- JS cells disabled
)

dotnet --version >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo .NET: (C# and F# cells enabled^)
    dotnet --version
) else (
    echo .NET: not found -- C#/F# cells disabled (install from https://dot.net^)
)
echo.

REM ── AI co-pilot context ─────────────────────────────────────────────────────
REM Export guardrail + skill + agent paths so the Arima JVM (and any CLI it
REM spawns for the in-UI AI panel) resolves them regardless of launch dir.
set "BARISTA_HOME=%CD%"
if exist "%CD%\AGENTS.md"       set "BARISTA_AGENTS_GUIDE=%CD%\AGENTS.md"
if exist "%CD%\.claude\skills"  set "BARISTA_SKILLS_DIR=%CD%\.claude\skills"
if exist "%CD%\.claude\agents"  set "BARISTA_AGENTS_DIR=%CD%\.claude\agents"
set "AI_FOUND="
where claude  >nul 2>&1 && set "AI_FOUND=!AI_FOUND! claude"
where copilot >nul 2>&1 && set "AI_FOUND=!AI_FOUND! copilot"
where gemini  >nul 2>&1 && set "AI_FOUND=!AI_FOUND! gemini"
if defined AI_FOUND (
    echo AI:     !AI_FOUND!  -- co-pilot ready, AGENTS.md + .claude loaded; run arima agents
) else (
    echo AI:     no CLI found -- install Claude / Copilot / Gemini for AI features
)
echo.

REM ── Build if JAR missing ───────────────────────────────────────────────────
set JAR=target\arima-notebooks-4.0.1.jar

if not exist "%JAR%" (
    echo Building Arima Notebooks...
    mvn clean package -DskipTests -q
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Build failed. Make sure Maven is installed.
        pause
        exit /b 1
    )
)

echo Starting Arima Notebooks...
echo Open http://localhost:8585 in your browser
echo.
echo Press Ctrl+C to stop   ^|   Use Restart in the UI to apply code changes
echo ---------------------------------------

REM ── Watchdog loop ─────────────────────────────────────────────────────────
REM Exit code 42 = restart requested from the UI.
:LOOP
java ^
    --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED ^
    --add-opens=java.base/java.lang=ALL-UNNAMED ^
    --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED ^
    -jar "%JAR%" %*

set EXIT_CODE=!ERRORLEVEL!

if !EXIT_CODE! == 42 (
    echo.
    echo ---------------------------------------
    echo   Restarting Arima Notebooks...
    echo ---------------------------------------
    timeout /t 1 /nobreak >nul
    if "%BARISTA_AUTO_BUILD%"=="1" (
        echo   Auto-building before restart...
        mvn package -DskipTests -q
    )
    goto LOOP
)

if !EXIT_CODE! neq 0 (
    echo.
    echo Arima Notebooks exited with code !EXIT_CODE!.
    pause
)

endlocal
