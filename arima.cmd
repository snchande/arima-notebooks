@echo off
setlocal enabledelayedexpansion
title Arima Notebooks CLI

REM ================================================================
REM  Arima Notebooks - Command Line Interface
REM  Usage: arima [command] [options]
REM ================================================================

cd /d "%~dp0"

REM Set ESC character for ANSI colors (Windows 10 1511+)
for /f %%a in ('echo prompt $E ^| cmd') do set "ESC=%%a"

REM ── AI co-pilot context ─────────────────────────────────────────────────────
REM Export guardrail + skill + agent paths so the Arima JVM (and any CLI it
REM spawns for the in-UI AI panel) resolves them regardless of launch dir.
set "BARISTA_HOME=%~dp0"
if "%BARISTA_HOME:~-1%"=="\" set "BARISTA_HOME=%BARISTA_HOME:~0,-1%"
if exist "%BARISTA_HOME%\AGENTS.md"       set "BARISTA_AGENTS_GUIDE=%BARISTA_HOME%\AGENTS.md"
if exist "%BARISTA_HOME%\.claude\skills"  set "BARISTA_SKILLS_DIR=%BARISTA_HOME%\.claude\skills"
if exist "%BARISTA_HOME%\.claude\agents"  set "BARISTA_AGENTS_DIR=%BARISTA_HOME%\.claude\agents"

REM Parse command
set "CMD=%~1"
if "%CMD%"=="" set "CMD=start"

REM Dispatch commands
if /i "%CMD%"=="start"   goto :cmd_start
if /i "%CMD%"=="stop"    goto :cmd_stop
if /i "%CMD%"=="status"  goto :cmd_status
if /i "%CMD%"=="build"   goto :cmd_build
if /i "%CMD%"=="rebuild" goto :cmd_rebuild
if /i "%CMD%"=="open"    goto :cmd_open
if /i "%CMD%"=="logs"    goto :cmd_logs
if /i "%CMD%"=="agents"  goto :cmd_agents
if /i "%CMD%"=="ai"      goto :cmd_agents
if /i "%CMD%"=="welcome" goto :cmd_welcome
if /i "%CMD%"=="docs"    goto :cmd_docs
if /i "%CMD%"=="help"    goto :cmd_help
if /i "%CMD%"=="-h"      goto :cmd_help
if /i "%CMD%"=="--help"  goto :cmd_help
if /i "%CMD%"=="version" goto :cmd_version

echo Unknown command: %CMD%
echo Run: arima help
exit /b 1


REM ================================================================
REM  BANNER  (Arima character + title)
REM  NOTE: pipe chars ^ escaped as ^| to prevent CMD treating as pipe
REM ================================================================
:banner
echo.
echo !ESC![93m        .        !ESC![0m!ESC![97m  A R I M A   N O T E B O O K S!ESC![0m
echo !ESC![93m       /^|\       !ESC![0m!ESC![90m  ─────────────────────────────!ESC![0m
echo !ESC![93m      ( @ )      !ESC![0m!ESC![96m  Java ^| JS ^| TS ^| C# ^| F# ^| C++ ^| Python!ESC![0m
echo !ESC![93m     /^|\_/^|\     !ESC![0m!ESC![90m  Brewed by Barista · JShell + Spring Boot!ESC![0m
echo !ESC![93m    / ^|   ^| \    !ESC![0m!ESC![90m  Port: 8585!ESC![0m
echo !ESC![93m   /__^|   ^|__\   !ESC![0m
echo !ESC![90m  ───────────────!ESC![0m
echo.
goto :eof


REM ================================================================
REM  START
REM ================================================================
:cmd_start
call :banner

REM Check if already running
curl -s -o nul http://localhost:8585 >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo !ESC![92m  Arima Notebooks is already running!!ESC![0m
    echo !ESC![96m  URL: http://localhost:8585!ESC![0m
    echo.
    set /p "OPEN=  Open in browser? [Y/n]: "
    if /i not "!OPEN!"=="n" start "" "http://localhost:8585"
    exit /b 0
)

REM Check Java
call :check_java
if %ERRORLEVEL% neq 0 exit /b 1

REM Check Node.js (optional — JavaScript / TypeScript cells)
node --version >nul 2>&1
if %ERRORLEVEL% == 0 (
    for /f "tokens=*" %%v in ('node --version') do echo !ESC![90m  Node.js:  %%v (JavaScript/TypeScript cells enabled)!ESC![0m
) else (
    echo !ESC![93m  Node.js:  not found -- JS/TS cells disabled (install from nodejs.org to enable)!ESC![0m
)

REM Check .NET SDK (optional — C# / F# cells)
dotnet --version >nul 2>&1
if %ERRORLEVEL% == 0 (
    for /f "tokens=*" %%v in ('dotnet --version') do echo !ESC![90m  .NET:     %%v (C# and F# cells enabled)!ESC![0m
) else (
    echo !ESC![93m  .NET:     not found -- C#/F# cells disabled (install from https://dot.net)!ESC![0m
)

REM Check Python (optional — Python cells + PyPI)
python --version >nul 2>&1
if %ERRORLEVEL% == 0 (
    for /f "tokens=*" %%v in ('python --version 2^>^&1') do echo !ESC![90m  Python:   %%v (Python cells + PyPI enabled)!ESC![0m
) else (
    echo !ESC![93m  Python:   not found -- Python cells disabled (install from https://www.python.org/downloads/)!ESC![0m
)

REM Show AI co-pilot readiness (context env already exported at top of script)
call :detect_copilots
if defined BARISTA_AI_COPILOTS echo !ESC![90m  AI:       !BARISTA_AI_COPILOTS!  co-pilot ready -- run: arima agents!ESC![0m
if not defined BARISTA_AI_COPILOTS echo !ESC![93m  AI:       no CLI found -- install Claude / Copilot / Gemini for AI features!ESC![0m

REM Check/build JAR
call :ensure_jar
if %ERRORLEVEL% neq 0 exit /b 1

REM Launch mode: --bg runs detached, default is foreground
if /i "%~2"=="--bg" goto :start_bg

echo !ESC![92m  Launching Arima Notebooks...!ESC![0m
echo !ESC![90m  Press Ctrl+C to stop!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo.

REM Open browser after short delay (background ping trick)
start "" cmd /c "ping -n 5 127.0.0.1 >nul && start http://localhost:8585"

java --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED -jar "target\arima-notebooks-1.0.0-SNAPSHOT.jar"
exit /b %ERRORLEVEL%

:start_bg
echo !ESC![96m  Starting Arima Notebooks in background...!ESC![0m
start /b "" java --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED -jar "target\arima-notebooks-1.0.0-SNAPSHOT.jar" > arima.log 2>&1
echo !ESC![92m  Started. Logs: arima.log!ESC![0m
echo !ESC![90m  Waiting for server...!ESC![0m
call :wait_for_server
echo !ESC![92m  Ready! Opening browser...!ESC![0m
start "" "http://localhost:8585"
exit /b 0


REM ================================================================
REM  STOP
REM ================================================================
:cmd_stop
echo.
echo !ESC![93m  Stopping Arima Notebooks...!ESC![0m
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8585 " ^| findstr "LISTENING"') do (
    echo !ESC![90m  Killing PID %%p!ESC![0m
    taskkill /PID %%p /F >nul 2>&1
    echo !ESC![92m  Stopped.!ESC![0m
    exit /b 0
)
echo !ESC![91m  Arima Notebooks is not running.!ESC![0m
exit /b 1


REM ================================================================
REM  STATUS
REM ================================================================
:cmd_status
echo.
curl -s -o nul http://localhost:8585 >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo !ESC![92m  [RUNNING]!ESC![0m Arima Notebooks is up at http://localhost:8585
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8585 " ^| findstr "LISTENING"') do (
        echo !ESC![90m  PID: %%p!ESC![0m
    )
) else (
    echo !ESC![91m  [STOPPED]!ESC![0m Arima Notebooks is not running.
)
echo.

set "JAR=target\arima-notebooks-1.0.0-SNAPSHOT.jar"
if exist "%JAR%"     echo !ESC![90m  JAR: %JAR% -- exists!ESC![0m
if not exist "%JAR%" echo !ESC![93m  JAR: not built yet -- run: arima build!ESC![0m

java -version >nul 2>&1
if %ERRORLEVEL% == 0 for /f "tokens=*" %%v in ('java -version 2^>^&1 ^| findstr "version"') do echo !ESC![90m  Java:     %%v!ESC![0m
if %ERRORLEVEL% neq 0 echo !ESC![91m  Java:     NOT FOUND!ESC![0m
node --version >nul 2>&1
if %ERRORLEVEL% == 0 for /f "tokens=*" %%v in ('node --version') do echo !ESC![90m  Node.js:  %%v  -- JS/TS cells enabled!ESC![0m
if %ERRORLEVEL% neq 0 echo !ESC![93m  Node.js:  not found -- JS/TS cells disabled, install from nodejs.org!ESC![0m
dotnet --version >nul 2>&1
if %ERRORLEVEL% == 0 for /f "tokens=*" %%v in ('dotnet --version') do echo !ESC![90m  .NET:     %%v  -- C# / F# cells enabled!ESC![0m
if %ERRORLEVEL% neq 0 echo !ESC![93m  .NET:     not found -- C# / F# cells disabled, install from https://dot.net!ESC![0m
python --version >nul 2>&1
if %ERRORLEVEL% == 0 for /f "tokens=*" %%v in ('python --version 2^>^&1') do echo !ESC![90m  Python:   %%v  -- Python cells + PyPI enabled!ESC![0m
if %ERRORLEVEL% neq 0 echo !ESC![93m  Python:   not found -- Python cells disabled, install from https://www.python.org/downloads/!ESC![0m

call :detect_copilots
if defined BARISTA_AI_COPILOTS echo !ESC![90m  AI:       !BARISTA_AI_COPILOTS!  co-pilot ready -- run: arima agents!ESC![0m
if not defined BARISTA_AI_COPILOTS echo !ESC![93m  AI:       no CLI found -- install Claude / Copilot / Gemini for AI features!ESC![0m
echo.
exit /b 0


REM ================================================================
REM  WELCOME  (common entry experience)
REM ================================================================
:cmd_welcome
call :banner
echo !ESC![97m  Welcome to Arima Notebooks!ESC![0m
echo !ESC![90m  A local notebook for Java ^| JS ^| TS ^| C# ^| F# ^| C++ ^| Python -- with AI co-pilots and MCP.!ESC![0m
echo.
echo !ESC![96m  PICK HOW YOU WANT TO WORK!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo !ESC![97m    1^) Open the UI!ESC![0m            !ESC![90mfull notebook experience in your browser!ESC![0m
echo !ESC![90m         arima start        -^>  http://localhost:8585!ESC![0m
echo !ESC![97m    2^) Drive Arima over MCP!ESC![0m   !ESC![90moperate ^& automate from any MCP client!ESC![0m
echo !ESC![90m         SSE  http://localhost:8585/api/mcp/sse!ESC![0m
echo !ESC![90m         POST http://localhost:8585/api/mcp/messages!ESC![0m
echo !ESC![97m    3^) Personalize ^& extend!ESC![0m   !ESC![90madd features -- needs an agentic CLI!ESC![0m
echo !ESC![90m         run  claude  /  copilot  /  gemini   in this folder, then ask the arima agent!ESC![0m
echo.
call :detect_copilots
if defined BARISTA_AI_COPILOTS echo !ESC![92m  AI co-pilots ready: !BARISTA_AI_COPILOTS!   run: arima agents!ESC![0m
if not defined BARISTA_AI_COPILOTS echo !ESC![93m  No AI CLI found -- install Claude, Copilot, or Gemini to personalize Arima.!ESC![0m
echo.
echo !ESC![96m  THE ONE DIFFERENCE!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo !ESC![90m    This arima CLI operates ^& automates Arima incl. MCP but cannot change its code.!ESC![0m
echo !ESC![90m    An agentic CLI claude / copilot / gemini can ALSO personalize and extend Arima.!ESC![0m
echo.
echo !ESC![96m  NEXT!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo     arima start      Start the server and open the UI
echo     arima docs       Open the brochure and list the docs
echo     arima agents     AI co-pilots, skills ^& the arima agent
echo.
echo !ESC![90m  Full welcome: docs\WELCOME.md!ESC![0m
echo.
exit /b 0


REM ================================================================
REM  DOCS  (open brochure + list documentation)
REM ================================================================
:cmd_docs
echo.
echo !ESC![97m  Arima Notebooks -- Documentation!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo     Brochure PDF     docs\brochure\arima-brochure.pdf
echo     Welcome          docs\WELCOME.md
echo     Getting started  README.md
echo     Architecture     docs\ARCHITECTURE.md
echo     API + MCP        docs\API.md
echo     Contributor      CONTRIBUTING.md  +  AGENTS.md
echo     Cheat sheet      docs\cheatsheet.html
echo.
if exist "docs\brochure\arima-brochure.pdf" echo !ESC![92m    Opening the brochure...!ESC![0m
if exist "docs\brochure\arima-brochure.pdf" start "" "docs\brochure\arima-brochure.pdf"
if not exist "docs\brochure\arima-brochure.pdf" echo !ESC![93m    Brochure PDF not found -- open docs\brochure\arima-brochure.html in a browser.!ESC![0m
echo.
exit /b 0


REM ================================================================
REM  AGENTS  (show AI co-pilots, skills, subagents)
REM ================================================================
:cmd_agents
echo.
echo !ESC![93m        .        !ESC![0m!ESC![97m  Arima AI Co-pilots, Skills ^& Agents!ESC![0m
echo !ESC![93m       /^|\       !ESC![0m!ESC![90m  ──────────────────────────────────────!ESC![0m
echo !ESC![93m      ( @ )      !ESC![0m
echo.
echo !ESC![96m  DETECTED AI CLIs!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
call :detect_copilots
if not defined BARISTA_AI_COPILOTS goto :agents_nocli
where claude  >nul 2>&1
if !ERRORLEVEL!==0 echo !ESC![92m    [ok] Claude!ESC![0m
where copilot >nul 2>&1
if !ERRORLEVEL!==0 echo !ESC![92m    [ok] Copilot!ESC![0m
where github-copilot-cli >nul 2>&1
if !ERRORLEVEL!==0 echo !ESC![92m    [ok] Copilot via github-copilot-cli!ESC![0m
where gh      >nul 2>&1
if !ERRORLEVEL!==0 echo !ESC![90m    [..] gh -- use: gh copilot!ESC![0m
where agy     >nul 2>&1
if !ERRORLEVEL!==0 echo !ESC![92m    [ok] Antigravity!ESC![0m
where gemini  >nul 2>&1
if !ERRORLEVEL!==0 echo !ESC![90m    [..] gemini -- retired 2026-06-18; use Antigravity (agy)!ESC![0m
goto :agents_after_clis
:agents_nocli
echo !ESC![93m    none found -- install one:!ESC![0m
echo !ESC![90m      Claude     :  https://claude.ai/code                      then  claude auth!ESC![0m
echo !ESC![90m      Copilot    :  GitHub Copilot CLI (copilot, v1.0.55-5+; used by the Copilot SDK)!ESC![0m
echo !ESC![90m      Antigravity:  https://antigravity.google/docs/cli-install  then  run  agy!ESC![0m
:agents_after_clis
echo.
echo !ESC![96m  GUARDRAILS read automatically by every AI CLI in this repo!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
if exist "AGENTS.md"                        echo !ESC![92m    [ok] AGENTS.md!ESC![0m
if not exist "AGENTS.md"                    echo !ESC![93m    [--] AGENTS.md -- missing!ESC![0m
if exist ".claude\skills"                   echo !ESC![92m    [ok] .claude\skills!ESC![0m
if not exist ".claude\skills"               echo !ESC![93m    [--] .claude\skills -- missing!ESC![0m
if exist ".claude\agents"                   echo !ESC![92m    [ok] .claude\agents!ESC![0m
if not exist ".claude\agents"               echo !ESC![93m    [--] .claude\agents -- missing!ESC![0m
if exist "CLAUDE.md"                        echo !ESC![92m    [ok] CLAUDE.md!ESC![0m
if not exist "CLAUDE.md"                    echo !ESC![93m    [--] CLAUDE.md -- missing!ESC![0m
if exist ".github\copilot-instructions.md"  echo !ESC![92m    [ok] .github\copilot-instructions.md!ESC![0m
if not exist ".github\copilot-instructions.md" echo !ESC![93m    [--] .github\copilot-instructions.md -- missing!ESC![0m
if exist "GEMINI.md"                        echo !ESC![92m    [ok] GEMINI.md!ESC![0m
if not exist "GEMINI.md"                    echo !ESC![93m    [--] GEMINI.md -- missing!ESC![0m
echo.
echo !ESC![96m  SKILLS (auto-invoke when your request matches)!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
if exist ".claude\skills" (
    for /d %%d in (".claude\skills\*") do echo !ESC![90m    - %%~nxd!ESC![0m
)
echo.
echo !ESC![96m  SUBAGENTS (spawn explicitly for a focused review)!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
if exist ".claude\agents" (
    for %%f in (".claude\agents\*.md") do echo !ESC![90m    - %%~nf!ESC![0m
)
echo.
echo !ESC![96m  HOW TO USE!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo     In the Arima UI : open the AI panel (Ctrl+\), pick a provider, ask away.
echo     In a terminal   : run your CLI from this folder so it loads AGENTS.md:
echo !ESC![90m                         claude        (or)   copilot        (or)   gemini!ESC![0m
echo     Example prompt  : "Add a Kotlin execution mode following CppExecutionService,
echo !ESC![90m                        then run pwsh .\scripts\security-check.ps1 and open a PR."!ESC![0m
echo.
echo !ESC![90m    Env exported for this session:!ESC![0m
echo !ESC![90m      BARISTA_HOME=%BARISTA_HOME%!ESC![0m
echo !ESC![90m      BARISTA_AGENTS_GUIDE=%BARISTA_AGENTS_GUIDE%!ESC![0m
echo !ESC![90m      BARISTA_SKILLS_DIR=%BARISTA_SKILLS_DIR%!ESC![0m
echo !ESC![90m      BARISTA_AGENTS_DIR=%BARISTA_AGENTS_DIR%!ESC![0m
echo.
exit /b 0


REM ================================================================
REM  BUILD
REM ================================================================
:cmd_build
call :banner
echo !ESC![96m  Building Arima Notebooks...!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
call :check_mvn
if %ERRORLEVEL% neq 0 exit /b 1
mvn clean package -DskipTests
if %ERRORLEVEL% == 0 (
    echo.
    echo !ESC![92m  Build successful!!ESC![0m
    echo !ESC![96m  Run: arima start!ESC![0m
) else (
    echo.
    echo !ESC![91m  Build failed. Check output above.!ESC![0m
)
exit /b %ERRORLEVEL%


REM ================================================================
REM  REBUILD  (force rebuild even if JAR exists)
REM ================================================================
:cmd_rebuild
call :banner
echo !ESC![93m  Rebuilding Arima Notebooks (force)...!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
call :check_mvn
if %ERRORLEVEL% neq 0 exit /b 1
mvn clean package -DskipTests
if %ERRORLEVEL% == 0 (
    echo.
    echo !ESC![92m  Rebuild successful!!ESC![0m
) else (
    echo.
    echo !ESC![91m  Rebuild failed. Check output above.!ESC![0m
)
exit /b %ERRORLEVEL%


REM ================================================================
REM  OPEN  (open browser without starting server)
REM ================================================================
:cmd_open
curl -s -o nul http://localhost:8585 >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo !ESC![92m  Opening Arima Notebooks...!ESC![0m
    start "" "http://localhost:8585"
) else (
    echo !ESC![91m  Arima Notebooks is not running. Start it first: arima start!ESC![0m
    exit /b 1
)
exit /b 0


REM ================================================================
REM  LOGS  (tail the arima.log if running in bg mode)
REM ================================================================
:cmd_logs
if not exist "arima.log" (
    echo !ESC![91m  No log file found (arima.log).!ESC![0m
    echo !ESC![90m  Logs are only written in background mode: arima start --bg!ESC![0m
    exit /b 1
)
echo !ESC![96m  Tailing arima.log (Ctrl+C to stop)...!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
powershell -Command "Get-Content -Path 'arima.log' -Wait -Tail 40"
exit /b 0


REM ================================================================
REM  VERSION
REM ================================================================
:cmd_version
echo.
echo !ESC![97m  Arima Notebooks!ESC![0m
if exist "pom.xml" (
    for /f "tokens=*" %%a in ('findstr "<version>" pom.xml ^| findstr /v "parent spring junit jackson boot"') do (
        echo !ESC![90m  Version:  %%a!ESC![0m
        goto :after_ver
    )
    :after_ver
)
for /f "tokens=*" %%v in ('java -version 2^>^&1 ^| findstr "version"') do echo !ESC![90m  Java:     %%v!ESC![0m
node --version >nul 2>&1
if %ERRORLEVEL% == 0 (
    for /f "tokens=*" %%v in ('node --version') do echo !ESC![90m  Node.js:  %%v!ESC![0m
) else (
    echo !ESC![93m  Node.js:  not installed!ESC![0m
)
dotnet --version >nul 2>&1
if %ERRORLEVEL% == 0 (
    for /f "tokens=*" %%v in ('dotnet --version') do echo !ESC![90m  .NET:     %%v!ESC![0m
)
where mvn >nul 2>&1
if %ERRORLEVEL% == 0 (
    for /f "tokens=*" %%v in ('mvn --version 2^>^&1 ^| findstr "Apache Maven"') do echo !ESC![90m  Maven:    %%v!ESC![0m
)
echo.
exit /b 0


REM ================================================================
REM  HELP
REM ================================================================
:cmd_help
echo.
echo !ESC![93m        .        !ESC![0m!ESC![97m  Arima Notebooks CLI!ESC![0m
echo !ESC![93m       /^|\       !ESC![0m!ESC![90m  ──────────────────────────────────────!ESC![0m
echo !ESC![93m      ( @ )      !ESC![0m!ESC![96m  Java ^| JS ^| TS ^| C# ^| F# ^| C++ ^| Python!ESC![0m
echo !ESC![93m     /^|\_/^|\     !ESC![0m
echo !ESC![93m    / ^|   ^| \    !ESC![0m!ESC![96m  USAGE!ESC![0m
echo !ESC![93m   /__^|   ^|__\   !ESC![0m!ESC![90m    arima [command] [options]!ESC![0m
echo !ESC![90m  ───────────────!ESC![0m
echo.
echo !ESC![96m  COMMANDS!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo    !ESC![97mstart!ESC![0m             Start server (auto-build, auto-open browser)
echo    !ESC![97mstart --bg!ESC![0m        Start in background, write logs to arima.log
echo    !ESC![97mstop!ESC![0m              Stop a running server
echo    !ESC![97mstatus!ESC![0m            Show server state, PID, Java, Node.js, .NET, JAR
echo    !ESC![97mbuild!ESC![0m             Compile and package (skips if JAR exists)
echo    !ESC![97mrebuild!ESC![0m           Force clean build
echo    !ESC![97mopen!ESC![0m              Open browser (server must already be running)
echo    !ESC![97mlogs!ESC![0m              Tail arima.log (background mode only)
echo    !ESC![97mversion!ESC![0m           Show Java, Node.js, .NET, Maven, and project version
echo    !ESC![97mwelcome!ESC![0m           Common welcome -- open the UI, use MCP, or personalize Arima
echo    !ESC![97mdocs!ESC![0m              Open the brochure and list the documentation
echo    !ESC![97magents!ESC![0m            Show AI co-pilots, skills ^& the arima agent wired into this repo
echo    !ESC![97mhelp!ESC![0m              Show this help
echo.
echo !ESC![96m  CELL MODES (seven languages)!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo    !ESC![36m◈ JShell!ESC![0m  Java snippets, shared session state (default)
echo    !ESC![36m◈ Java!ESC![0m    Full compile-and-run, isolated per cell
echo    !ESC![32m◈ JS!ESC![0m      Node.js JavaScript, isolated per cell
echo    !ESC![34m◆ TS!ESC![0m      TypeScript via Node type-stripping (Node 22.6+)
echo    !ESC![35m◈ C#!ESC![0m      dotnet run, top-level program
echo    !ESC![33m◈ F#!ESC![0m      dotnet fsi script
echo    !ESC![36m◈ C++!ESC![0m     MSVC / GCC / Clang compile-and-run
echo    Cycle a cell's mode button: JShell -^> Java -^> JS -^> TS -^> C# -^> F# -^> C++
echo.
echo !ESC![96m  PACKAGES!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo    Maven (JShell/Java) ^| npm (JS/TS) ^| NuGet (C#/F#) -- all from the Packages tab
echo    Popular npm: simple-statistics, mathjs, d3-array, lodash, axios
echo.
echo !ESC![96m  EXAMPLES!ESC![0m
echo !ESC![90m  ──────────────────────────────────────!ESC![0m
echo    arima                   (same as: arima start)
echo    arima start             Start foreground, auto-open browser
echo    arima start --bg        Start in background
echo    arima stop              Kill the server
echo    arima status            Check server + Java + Node.js + .NET
echo    arima rebuild           Force rebuild then: arima start
echo    arima logs              Stream background logs
echo    arima version           Show all tool versions
echo.
echo !ESC![96m  URL!ESC![0m
echo    http://localhost:8585
echo.
exit /b 0


REM ================================================================
REM  HELPERS
REM ================================================================

:detect_copilots
set "BARISTA_AI_COPILOTS="
where claude  >nul 2>&1 && set "BARISTA_AI_COPILOTS=!BARISTA_AI_COPILOTS! Claude"
where copilot >nul 2>&1 && set "BARISTA_AI_COPILOTS=!BARISTA_AI_COPILOTS! Copilot"
if not defined BARISTA_AI_COPILOTS where github-copilot-cli >nul 2>&1 && set "BARISTA_AI_COPILOTS=!BARISTA_AI_COPILOTS! Copilot"
where agy     >nul 2>&1 && set "BARISTA_AI_COPILOTS=!BARISTA_AI_COPILOTS! Antigravity"
REM Trim leading space
if defined BARISTA_AI_COPILOTS if "!BARISTA_AI_COPILOTS:~0,1!"==" " set "BARISTA_AI_COPILOTS=!BARISTA_AI_COPILOTS:~1!"
exit /b 0

:check_java
java -version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo !ESC![91m  ERROR: Java not found.!ESC![0m
    echo !ESC![90m  Install JDK 21+ from: https://adoptium.net/!ESC![0m
    exit /b 1
)
for /f "tokens=*" %%v in ('java -version 2^>^&1 ^| findstr "version"') do echo !ESC![90m  Java: %%v!ESC![0m
exit /b 0

:check_mvn
where mvn >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo !ESC![91m  ERROR: Maven not found.!ESC![0m
    echo !ESC![90m  Install from: https://maven.apache.org/!ESC![0m
    exit /b 1
)
exit /b 0

:ensure_jar
set "JAR=target\arima-notebooks-1.0.0-SNAPSHOT.jar"
if not exist "%JAR%" (
    echo !ESC![93m  JAR not found -- building first...!ESC![0m
    call :check_mvn
    if %ERRORLEVEL% neq 0 exit /b 1
    mvn clean package -DskipTests -q
    if %ERRORLEVEL% neq 0 (
        echo !ESC![91m  Build failed.!ESC![0m
        exit /b 1
    )
    echo !ESC![92m  Build complete.!ESC![0m
)
exit /b 0

:wait_for_server
set /a TRIES=0
:wait_loop
ping -n 2 127.0.0.1 >nul
curl -s -o nul http://localhost:8585 >nul 2>&1
if %ERRORLEVEL% == 0 exit /b 0
set /a TRIES+=1
if %TRIES% lss 15 goto :wait_loop
echo !ESC![91m  Server did not respond after 30s -- check arima.log!ESC![0m
exit /b 1
