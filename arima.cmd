@echo off
setlocal enabledelayedexpansion
title Arima Notebooks CLI

REM ============================================================================
REM  Arima Notebooks - Windows CMD launcher
REM
REM  Shares one look, one animation vocabulary and one command set with
REM  arima.ps1 (PowerShell) and arima.sh (Linux/macOS).
REM
REM  IMPORTANT FOR EDITORS: this file must keep CRLF line endings. cmd.exe
REM  mis-parses LF-only batch files ("... is not recognized", "was unexpected
REM  at this time"). .gitattributes pins *.cmd to CRLF - do not override it.
REM  Everything printed below is deliberately 7-bit ASCII so the output is
REM  identical under every console code page.
REM ============================================================================

cd /d "%~dp0"

set "ARIMA_PORT=8585"
set "ARIMA_URL=http://localhost:8585"
set "ARIMA_JAR=target\arima-notebooks-1.0.0-SNAPSHOT.jar"
set "ARIMA_MCP=%ARIMA_URL%/api/mcp/messages"
set "MIN_JAVA=17"
set "RULE=------------------------------------------------------------"
set "ARIMA_TAGLINE=A local-first, AI-native notebook for eight languages - run code, build pipelines, and drive it all over MCP."

call :init_colors

REM -- AI co-pilot context ----------------------------------------------------
REM Export guardrail + skill + agent paths so the Arima JVM (and any CLI it
REM spawns for the in-UI AI panel) resolves them regardless of launch dir.
set "BARISTA_HOME=%~dp0"
if "%BARISTA_HOME:~-1%"=="\" set "BARISTA_HOME=%BARISTA_HOME:~0,-1%"
if exist "%BARISTA_HOME%\AGENTS.md"       set "BARISTA_AGENTS_GUIDE=%BARISTA_HOME%\AGENTS.md"
if exist "%BARISTA_HOME%\.claude\skills"  set "BARISTA_SKILLS_DIR=%BARISTA_HOME%\.claude\skills"
if exist "%BARISTA_HOME%\.claude\agents"  set "BARISTA_AGENTS_DIR=%BARISTA_HOME%\.claude\agents"

REM -- Flags ------------------------------------------------------------------
set "F_BG=0"
set "F_YES=0"
set "F_PURGE=0"
set "F_NOBUILD=0"
set "F_PATH=0"
set "F_SKIPOPT=0"
call :parse_flags %*

set "CMD=%~1"
if "%CMD%"=="" set "CMD=home"

if /i "%CMD%"=="home"      goto :cmd_home
if /i "%CMD%"=="install"   goto :cmd_install
if /i "%CMD%"=="update"    goto :cmd_update
if /i "%CMD%"=="upgrade"   goto :cmd_update
if /i "%CMD%"=="uninstall" goto :cmd_uninstall
if /i "%CMD%"=="start"     goto :cmd_start
if /i "%CMD%"=="stop"      goto :cmd_stop
if /i "%CMD%"=="restart"   goto :cmd_restart
if /i "%CMD%"=="status"    goto :cmd_status
if /i "%CMD%"=="open"      goto :cmd_open
if /i "%CMD%"=="logs"      goto :cmd_logs
if /i "%CMD%"=="build"     goto :cmd_build
if /i "%CMD%"=="rebuild"   goto :cmd_build
if /i "%CMD%"=="mcp"       goto :cmd_mcp
if /i "%CMD%"=="brew"      goto :cmd_brew
if /i "%CMD%"=="coffee"    goto :cmd_brew
if /i "%CMD%"=="version"   goto :cmd_version
if /i "%CMD%"=="--version" goto :cmd_version
if /i "%CMD%"=="agents"    goto :cmd_agents
if /i "%CMD%"=="ai"        goto :cmd_agents
if /i "%CMD%"=="welcome"   goto :cmd_welcome
if /i "%CMD%"=="docs"      goto :cmd_docs
if /i "%CMD%"=="help"      goto :cmd_help
if /i "%CMD%"=="-h"        goto :cmd_help
if /i "%CMD%"=="--help"    goto :cmd_help

call :err "Unknown command: %CMD%"
call :dim "Run: arima help"
exit /b 1


REM ============================================================================
REM  SHARED LOOK AND FEEL
REM ============================================================================

:init_colors
REM Resolve the ESC character so ANSI colours work on Windows 10/11 consoles.
for /f "delims=#" %%E in ('"prompt #$E# & for %%a in (1) do rem"') do set "ESC=%%E"
set "C_RST=%ESC%[0m"
set "C_DIM=%ESC%[90m"
set "C_RED=%ESC%[91m"
set "C_GRN=%ESC%[92m"
set "C_YEL=%ESC%[93m"
set "C_BRN=%ESC%[33m"
set "C_CYN=%ESC%[96m"
set "C_WHT=%ESC%[97m"
REM Opt out on terminals without VT support: set ARIMA_NO_COLOR=1
if not defined ARIMA_NO_COLOR goto :eof
set "C_RST="
set "C_DIM="
set "C_RED="
set "C_GRN="
set "C_YEL="
set "C_BRN="
set "C_CYN="
set "C_WHT="
set "ESC="
exit /b 0

:parse_flags
if "%~1"=="" exit /b 0
if /i "%~1"=="--bg"            set "F_BG=1"
if /i "%~1"=="-b"              set "F_BG=1"
if /i "%~1"=="--yes"           set "F_YES=1"
if /i "%~1"=="-y"              set "F_YES=1"
if /i "%~1"=="--purge"         set "F_PURGE=1"
if /i "%~1"=="--no-build"      set "F_NOBUILD=1"
if /i "%~1"=="--path"          set "F_PATH=1"
if /i "%~1"=="--skip-optional" set "F_SKIPOPT=1"
if /i "%~1"=="--no-anim"       set "ARIMA_NO_ANIM=1"
shift
goto :parse_flags

:say
echo %~1
exit /b 0
:title_
echo %C_WHT%%~1%C_RST%
exit /b 0
:info
echo %C_CYN%%~1%C_RST%
exit /b 0
:ok
echo %C_GRN%%~1%C_RST%
exit /b 0
:warn
echo %C_YEL%%~1%C_RST%
exit /b 0
:err
echo %C_RED%%~1%C_RST%
exit /b 0
:dim
echo %C_DIM%%~1%C_RST%
exit /b 0

REM Sub-second pause used by the banner reveal. 192.0.2.1 is TEST-NET-1 and
REM never answers, so ping simply waits out its -w timeout.
:napms
if defined ARIMA_NO_ANIM exit /b 0
ping -n 1 -w %~1 192.0.2.1 >nul 2>&1
exit /b 0

:section
echo.
echo %C_CYN%  %~1%C_RST%
echo %C_DIM%  %RULE%%C_RST%
exit /b 0

REM :step <index> <total> <text>
:step
echo.
echo %C_CYN%  [%~1/%~2] %C_RST%%C_WHT% %~3%C_RST%
exit /b 0

REM :row <ok^|warn^|err^|dots> <label> <detail>
:row
set "R_LBL=%~2               "
set "R_LBL=!R_LBL:~0,13!"
if /i "%~1"=="ok"   echo %C_GRN%    [ok] %C_RST%%C_WHT%!R_LBL!%C_RST%%C_DIM%%~3%C_RST%
if /i "%~1"=="warn" echo %C_YEL%    [--] %C_RST%%C_WHT%!R_LBL!%C_RST%%C_DIM%%~3%C_RST%
if /i "%~1"=="err"  echo %C_RED%    [^^!^^!] %C_RST%%C_WHT%!R_LBL!%C_RST%%C_DIM%%~3%C_RST%
if /i "%~1"=="dots" echo %C_DIM%    [..] %C_RST%%C_WHT%!R_LBL!%C_RST%%C_DIM%%~3%C_RST%
exit /b 0

REM :bar <percent> <label>   ->  [########............]  40%  label
:bar
set /a B_PCT=%~1
set /a B_FILL=(B_PCT*20)/100
set "B_STR="
set /a B_I=0
:bar_fill
if !B_I! geq !B_FILL! goto :bar_pad
set "B_STR=!B_STR!#"
set /a B_I+=1
goto :bar_fill
:bar_pad
if !B_I! geq 20 goto :bar_out
set "B_STR=!B_STR!."
set /a B_I+=1
goto :bar_pad
:bar_out
echo %C_DIM%  [!B_STR!] !B_PCT!%% %~2%C_RST%
call :napms 40
exit /b 0

REM ---------------------------------------------------------------------------
REM  THE BARISTA BREW ANIMATION
REM  A coffee bean drops into the cup, Barista brews it, and serves it steaming.
REM  Eight frames redrawn in place with ANSI cursor-up (ESC[8A); the same art,
REM  timing and captions ship in arima.ps1 and arima.sh.
REM  Rows 0-1 = bean / steam, rows 2-6 = the cup, row 7 = the caption.
REM ---------------------------------------------------------------------------
:coffee
if defined ARIMA_NO_ANIM goto :coffee_static
if not defined ESC goto :coffee_static
echo.
call :cf1
call :cf_up 130
call :cf2
call :cf_up 130
call :cf3
call :cf_up 130
call :cf4
call :cf_up 130
call :cf5
call :cf_up 130
call :cf6
call :cf_up 190
call :cf7
call :cf_up 190
call :cf8
call :cf_up 190
call :cf7
call :cf_up 190
call :cf8
exit /b 0

REM Pause, then move the cursor back up over the 8 frame lines.
:cf_up
ping -n 1 -w %~1 192.0.2.1 >nul 2>&1
<nul set /p "=!ESC![8A"
exit /b 0

:cf1
echo !ESC![2K%C_YEL%        ^(@^)%C_RST%
echo !ESC![2K
echo !ESC![2K%C_WHT%        .------.%C_RST%
echo !ESC![2K%C_BRN%        ^|      ^|]%C_RST%
echo !ESC![2K%C_BRN%        ^|      ^|%C_RST%
echo !ESC![2K%C_WHT%        '------'%C_RST%
echo !ESC![2K%C_DIM%       ~~~~~~~~~~%C_RST%
echo !ESC![2K%C_CYN%   Barista picks a bean...%C_RST%
exit /b 0

:cf2
echo !ESC![2K
echo !ESC![2K%C_YEL%        ^(@^)%C_RST%
echo !ESC![2K%C_WHT%        .------.%C_RST%
echo !ESC![2K%C_BRN%        ^|      ^|]%C_RST%
echo !ESC![2K%C_BRN%        ^|      ^|%C_RST%
echo !ESC![2K%C_WHT%        '------'%C_RST%
echo !ESC![2K%C_DIM%       ~~~~~~~~~~%C_RST%
echo !ESC![2K%C_CYN%   Barista picks a bean...%C_RST%
exit /b 0

:cf3
echo !ESC![2K
echo !ESC![2K
echo !ESC![2K%C_WHT%        .------.%C_RST%
echo !ESC![2K%C_BRN%        ^|  *   ^|]%C_RST%
echo !ESC![2K%C_BRN%        ^|      ^|%C_RST%
echo !ESC![2K%C_WHT%        '------'%C_RST%
echo !ESC![2K%C_DIM%       ~~~~~~~~~~%C_RST%
echo !ESC![2K%C_CYN%   Barista grinds it...%C_RST%
exit /b 0

:cf4
echo !ESC![2K
echo !ESC![2K
echo !ESC![2K%C_WHT%        .------.%C_RST%
echo !ESC![2K%C_BRN%        ^|      ^|]%C_RST%
echo !ESC![2K%C_BRN%        ^|::::::^|%C_RST%
echo !ESC![2K%C_WHT%        '------'%C_RST%
echo !ESC![2K%C_DIM%       ~~~~~~~~~~%C_RST%
echo !ESC![2K%C_CYN%   Barista is brewing...%C_RST%
exit /b 0

:cf5
echo !ESC![2K
echo !ESC![2K
echo !ESC![2K%C_WHT%        .------.%C_RST%
echo !ESC![2K%C_BRN%        ^|::::::^|]%C_RST%
echo !ESC![2K%C_BRN%        ^|######^|%C_RST%
echo !ESC![2K%C_WHT%        '------'%C_RST%
echo !ESC![2K%C_DIM%       ~~~~~~~~~~%C_RST%
echo !ESC![2K%C_CYN%   Barista is brewing...%C_RST%
exit /b 0

:cf6
echo !ESC![2K%C_DIM%          ^(%C_RST%
echo !ESC![2K%C_DIM%           ^)%C_RST%
echo !ESC![2K%C_WHT%        .------.%C_RST%
echo !ESC![2K%C_BRN%        ^|######^|]%C_RST%
echo !ESC![2K%C_BRN%        ^|######^|%C_RST%
echo !ESC![2K%C_WHT%        '------'%C_RST%
echo !ESC![2K%C_DIM%       ~~~~~~~~~~%C_RST%
echo !ESC![2K%C_CYN%   Barista is brewing...%C_RST%
exit /b 0

:cf7
echo !ESC![2K%C_DIM%         ^( ^)%C_RST%
echo !ESC![2K%C_DIM%          ^) ^(%C_RST%
echo !ESC![2K%C_WHT%        .------.%C_RST%
echo !ESC![2K%C_BRN%        ^|######^|]%C_RST%
echo !ESC![2K%C_BRN%        ^|######^|%C_RST%
echo !ESC![2K%C_WHT%        '------'%C_RST%
echo !ESC![2K%C_DIM%       ~~~~~~~~~~%C_RST%
echo !ESC![2K%C_CYN%   Barista serves your notebook.%C_RST%
exit /b 0

:cf8
echo !ESC![2K%C_DIM%          ^) ^(%C_RST%
echo !ESC![2K%C_DIM%         ^( ^)%C_RST%
echo !ESC![2K%C_WHT%        .------.%C_RST%
echo !ESC![2K%C_BRN%        ^|######^|]%C_RST%
echo !ESC![2K%C_BRN%        ^|######^|%C_RST%
echo !ESC![2K%C_WHT%        '------'%C_RST%
echo !ESC![2K%C_DIM%       ~~~~~~~~~~%C_RST%
echo !ESC![2K%C_CYN%   Barista serves your notebook.%C_RST%
exit /b 0

REM Non-animated fallback (piped output, ARIMA_NO_COLOR, or --no-anim):
REM print the served cup once. Every line has a visible glyph so plain `echo`
REM never degrades into "ECHO is on".
:coffee_static
echo.
echo %C_DIM%         ^( ^)%C_RST%
echo %C_DIM%          ^) ^(%C_RST%
echo %C_WHT%        .------.%C_RST%
echo %C_BRN%        ^|######^|]%C_RST%
echo %C_BRN%        ^|######^|%C_RST%
echo %C_WHT%        '------'%C_RST%
echo %C_DIM%       ~~~~~~~~~~%C_RST%
echo %C_CYN%   Barista serves your notebook.%C_RST%
exit /b 0

REM Banner. Override BN_HEAD / BN_L1..BN_L4 before calling; they reset after.
:banner
if not defined BN_HEAD set "BN_HEAD=A R I M A   N O T E B O O K S"
if not defined BN_L1   set "BN_L1=------------------------------------------"
if not defined BN_L2   set "BN_L2=Java  JShell  JS  TS  C#  F#  C++  Python"
if not defined BN_L3   set "BN_L3=Brewed by Barista - JShell + Spring Boot"
if not defined BN_L4   set "BN_L4=Server: %ARIMA_URL%"
echo.
echo %C_YEL%     .-"""""-.    %C_RST%%C_WHT%  !BN_HEAD!%C_RST%
call :napms 55
echo %C_YEL%   .'    \    '.  %C_RST%%C_DIM%  !BN_L1!%C_RST%
call :napms 55
echo %C_YEL%  /      ^)      \ %C_RST%%C_CYN%  !BN_L2!%C_RST%
call :napms 55
echo %C_YEL%  \      ^(      / %C_RST%%C_DIM%  !BN_L3!%C_RST%
call :napms 55
echo %C_YEL%   '.    /    .'  %C_RST%%C_DIM%  !BN_L4!%C_RST%
call :napms 55
echo %C_YEL%     '-.....-'    %C_RST%
echo %C_DIM%  %RULE%%C_RST%
echo.
set "BN_HEAD="
set "BN_L1="
set "BN_L2="
set "BN_L3="
set "BN_L4="
exit /b 0


REM ============================================================================
REM  PROBES
REM ============================================================================

:have
where %~1 >nul 2>&1
exit /b !ERRORLEVEL!

:server_up
netstat -ano | findstr ":%ARIMA_PORT% " | findstr "LISTENING" >nul 2>&1
exit /b !ERRORLEVEL!

:get_pid
set "SRV_PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%ARIMA_PORT% " ^| findstr "LISTENING"') do set "SRV_PID=%%p"
exit /b 0

REM Sets JAVA_MAJOR (0 when Java is absent) and JAVA_LINE.
:java_probe
set "JAVA_MAJOR=0"
set "JAVA_LINE="
call :have java
if not "!ERRORLEVEL!"=="0" exit /b 0
for /f "tokens=*" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do if not defined JAVA_LINE set "JAVA_LINE=%%v"
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do if "!JAVA_MAJOR!"=="0" set "JV=%%~v"
if not defined JV exit /b 0
for /f "delims=. tokens=1" %%a in ("!JV!") do set "JAVA_MAJOR=%%a"
if "!JAVA_MAJOR!"=="1" for /f "delims=. tokens=2" %%a in ("!JV!") do set "JAVA_MAJOR=%%a"
exit /b 0

REM Sets PY_BIN to the first usable Python launcher, or clears it.
:python_probe
set "PY_BIN="
call :have python
if "!ERRORLEVEL!"=="0" set "PY_BIN=python" & exit /b 0
call :have python3
if "!ERRORLEVEL!"=="0" set "PY_BIN=python3" & exit /b 0
call :have py
if "!ERRORLEVEL!"=="0" set "PY_BIN=py"
exit /b 0

REM Sets CXX_BIN to the first C++ compiler found, or clears it.
:cpp_probe
set "CXX_BIN="
call :have cl
if "!ERRORLEVEL!"=="0" set "CXX_BIN=cl" & exit /b 0
call :have g++
if "!ERRORLEVEL!"=="0" set "CXX_BIN=g++" & exit /b 0
call :have clang++
if "!ERRORLEVEL!"=="0" set "CXX_BIN=clang++"
exit /b 0

:detect_copilots
set "BARISTA_AI_COPILOTS="
call :have claude
if "!ERRORLEVEL!"=="0" set "BARISTA_AI_COPILOTS=!BARISTA_AI_COPILOTS! Claude"
call :have copilot
if "!ERRORLEVEL!"=="0" set "BARISTA_AI_COPILOTS=!BARISTA_AI_COPILOTS! Copilot"
call :have agy
if "!ERRORLEVEL!"=="0" set "BARISTA_AI_COPILOTS=!BARISTA_AI_COPILOTS! Antigravity"
if not defined BARISTA_AI_COPILOTS exit /b 0
if "!BARISTA_AI_COPILOTS:~0,1!"==" " set "BARISTA_AI_COPILOTS=!BARISTA_AI_COPILOTS:~1!"
exit /b 0

:show_copilots
call :detect_copilots
if defined BARISTA_AI_COPILOTS call :row ok   "AI" "!BARISTA_AI_COPILOTS!  (co-pilot ready)"
if not defined BARISTA_AI_COPILOTS call :row warn "AI" "no CLI found  (install Claude, Copilot, or Antigravity)"
if exist "AGENTS.md" if exist ".claude\skills" if exist ".claude\agents" call :row ok "Guardrails" "AGENTS.md + skills/ + agents/ loaded  (run: arima agents)"
if not exist "AGENTS.md" call :row warn "Guardrails" "AGENTS.md missing"
exit /b 0

REM Prints the full runtime inventory, shared by install / status / version.
:show_runtimes
call :java_probe
if !JAVA_MAJOR! geq %MIN_JAVA% call :row ok  "Java" "!JAVA_LINE!"
if !JAVA_MAJOR! lss %MIN_JAVA% if not "!JAVA_MAJOR!"=="0" call :row err "Java" "!JAVA_LINE! -- JDK %MIN_JAVA%+ required"
if "!JAVA_MAJOR!"=="0" call :row err "Java" "NOT FOUND -- install JDK %MIN_JAVA%+ from https://adoptium.net/"

call :have mvn
if "!ERRORLEVEL!"=="0" goto :sr_mvn_ok
call :row err "Maven" "NOT FOUND -- needed to build (https://maven.apache.org/)"
goto :sr_node
:sr_mvn_ok
set "MVN_LINE="
for /f "tokens=*" %%v in ('mvn --version 2^>^&1 ^| findstr /i "Apache Maven"') do if not defined MVN_LINE set "MVN_LINE=%%v"
call :row ok "Maven" "!MVN_LINE!"

:sr_node
call :have node
if not "!ERRORLEVEL!"=="0" goto :sr_node_miss
for /f "tokens=*" %%v in ('node --version') do call :row ok "Node.js" "%%v  -- JS / TS cells"
goto :sr_tsc
:sr_node_miss
call :row warn "Node.js" "not found -- JS / TS cells disabled (nodejs.org)"

:sr_tsc
call :have tsc
if "!ERRORLEVEL!"=="0" call :row ok "tsc" "found -- TypeScript type-check diagnostics on"

call :have dotnet
if not "!ERRORLEVEL!"=="0" goto :sr_net_miss
for /f "tokens=*" %%v in ('dotnet --version') do call :row ok ".NET" "%%v  -- C# / F# cells"
goto :sr_py
:sr_net_miss
call :row warn ".NET" "not found -- C# / F# cells disabled (https://dot.net)"

:sr_py
call :python_probe
if not defined PY_BIN goto :sr_py_miss
for /f "tokens=*" %%v in ('!PY_BIN! --version 2^>^&1') do call :row ok "Python" "%%v  -- Python cells + PyPI"
goto :sr_cpp
:sr_py_miss
call :row warn "Python" "not found -- Python cells disabled (python.org)"

:sr_cpp
call :cpp_probe
if defined CXX_BIN call :row ok "C++" "!CXX_BIN!  -- C++ cells"
if not defined CXX_BIN call :row warn "C++" "no compiler found -- C++ cells disabled (MSVC / GCC / Clang)"

call :have git
if "!ERRORLEVEL!"=="0" call :row ok   "Git" "found -- 'arima update' can sync with upstream"
if not "!ERRORLEVEL!"=="0" call :row warn "Git" "not found -- 'arima update' unavailable"
exit /b 0

:ensure_jar
if exist "%ARIMA_JAR%" exit /b 0
call :warn "  JAR not found -- building first..."
call :have mvn
if not "!ERRORLEVEL!"=="0" goto :ensure_jar_nomvn
call mvn clean package -DskipTests -q
if not "!ERRORLEVEL!"=="0" goto :ensure_jar_fail
call :ok "  Build complete."
exit /b 0
:ensure_jar_nomvn
call :err "  ERROR: Maven not found. Install from https://maven.apache.org/"
exit /b 1
:ensure_jar_fail
call :err "  Build failed."
exit /b 1

REM Spinner-backed wait, sharing its frame set with arima.ps1 / arima.sh.
:wait_server
set /a WS_N=0
set /a WS_MAX=90
:ws_loop
call :server_up
if "!ERRORLEVEL!"=="0" goto :ws_up
set /a WS_MOD=WS_N %% 4
if !WS_MOD!==0 set "WS_F=-"
if !WS_MOD!==1 set "WS_F=\"
if !WS_MOD!==2 set "WS_F=^|"
if !WS_MOD!==3 set "WS_F=/"
set /a WS_LEFT=(WS_MAX-WS_N)/2
if not defined ARIMA_NO_ANIM <nul set /p "=!ESC![2K!ESC![1G%C_DIM%  [!WS_F!]  waiting for the server ... !WS_LEFT!s%C_RST%"
ping -n 1 -w 450 192.0.2.1 >nul 2>&1
set /a WS_N+=1
if !WS_N! lss !WS_MAX! goto :ws_loop
if not defined ARIMA_NO_ANIM <nul set /p "=!ESC![2K!ESC![1G"
exit /b 1
:ws_up
if not defined ARIMA_NO_ANIM <nul set /p "=!ESC![2K!ESC![1G"
exit /b 0

REM :confirm <prompt>   -> errorlevel 0 when the user typed "yes"
:confirm
if "%F_YES%"=="1" call :dim "  (--yes) %~1 -> yes" & exit /b 0
echo.
set "ANS="
set /p "ANS=%C_YEL%  %~1 %C_RST%%C_DIM%[type 'yes' to confirm]: %C_RST%"
if /i "!ANS!"=="yes" exit /b 0
exit /b 1


REM ============================================================================
REM  INSTALL  - check every dependency, install what is missing, report status
REM ============================================================================
:cmd_install
set "BN_HEAD=A R I M A   -   I N S T A L L"
set "BN_L2=Check every dependency, install what is missing,"
set "BN_L3=build the JAR, and report readiness."
call :banner

call :step 1 6 "Checking dependencies"
call :bar 10 "probing toolchains"
call :scan_deps
call :show_deps

call :step 2 6 "Installing missing dependencies"
if "!MISS_N!"=="0" goto :inst_nothing
call :have winget
if not "!ERRORLEVEL!"=="0" goto :inst_nowinget
echo.
call :warn "  These will be installed with winget (system-wide):"
if "!M_JAVA!"=="1"   call :dim "      Java      EclipseAdoptium.Temurin.21.JDK"
if "!M_MVN!"=="1"    call :dim "      Maven     Apache.Maven"
if "!M_GIT!"=="1"    call :dim "      Git       Git.Git"
if "!M_NODE!"=="1"   call :dim "      Node.js   OpenJS.NodeJS.LTS"
if "!M_NET!"=="1"    call :dim "      .NET      Microsoft.DotNet.SDK.9"
if "!M_PY!"=="1"     call :dim "      Python    Python.Python.3.13"
if "!M_CPP!"=="1"    call :dim "      C++       Microsoft.VisualStudio.2022.BuildTools  (LARGE, several GB)"
call :confirm "Install the packages listed above?"
if not "!ERRORLEVEL!"=="0" goto :inst_declined
echo.
if "!M_JAVA!"=="1" call :winget_install "Java"    "EclipseAdoptium.Temurin.21.JDK"
if "!M_MVN!"=="1"  call :winget_install "Maven"   "Apache.Maven"
if "!M_GIT!"=="1"  call :winget_install "Git"     "Git.Git"
if "!M_NODE!"=="1" call :winget_install "Node.js" "OpenJS.NodeJS.LTS"
if "!M_NET!"=="1"  call :winget_install ".NET"    "Microsoft.DotNet.SDK.9"
if "!M_PY!"=="1"   call :winget_install "Python"  "Python.Python.3.13"
if "!M_CPP!"=="1"  call :winget_install "C++"     "Microsoft.VisualStudio.2022.BuildTools"
call :refresh_path
goto :inst_workspace

:inst_nothing
call :bar 100 "nothing to install"
call :row ok "Dependencies" "all present"
goto :inst_workspace

:inst_nowinget
call :bar 100 "winget unavailable"
call :row err "winget" "not available -- install these by hand:"
if "!M_JAVA!"=="1" call :dim "           Java      https://adoptium.net/"
if "!M_MVN!"=="1"  call :dim "           Maven     https://maven.apache.org/"
if "!M_GIT!"=="1"  call :dim "           Git       https://git-scm.com/"
if "!M_NODE!"=="1" call :dim "           Node.js   https://nodejs.org/"
if "!M_NET!"=="1"  call :dim "           .NET      https://dot.net/"
if "!M_PY!"=="1"   call :dim "           Python    https://www.python.org/downloads/"
if "!M_CPP!"=="1"  call :dim "           C++       https://visualstudio.microsoft.com/downloads/"
goto :inst_workspace

:inst_declined
echo.
call :warn "  Skipped -- no packages were installed."
call :dim  "  Re-run with --yes to install without prompting, or --skip-optional for the minimum."

:inst_workspace
call :step 3 6 "Preparing the workspace"
call :bar 100 "creating folders"
call :ensure_dir data
call :ensure_dir notebooks
call :ensure_dir logs

call :step 4 6 "Wiring AI co-pilots"
call :bar 100 "detecting AI CLIs"
call :show_copilots

call :scan_deps
call :step 5 6 "Building the JAR"
if "!M_JAVA!"=="1" goto :inst_blocked
if "!M_MVN!"=="1"  goto :inst_blocked
if "%F_NOBUILD%"=="1" goto :inst_nobuild
call :bar 50 "mvn clean package -DskipTests"
echo.
call mvn clean package -DskipTests
if not "!ERRORLEVEL!"=="0" goto :inst_buildfail
call :bar 100 "build complete"
call :row ok "JAR" "%ARIMA_JAR%"
goto :inst_path

:inst_nobuild
call :bar 100 "skipped (--no-build)"
goto :inst_path

:inst_blocked
call :row err "Build" "skipped -- a required tool is still missing"
goto :inst_path

:inst_buildfail
echo.
call :err "  Build failed -- see the Maven output above."
exit /b 1

:inst_path
call :step 6 6 "Registering on your user PATH"
if not "%F_PATH%"=="1" goto :inst_path_skip
echo "%PATH%" | findstr /i /c:"%BARISTA_HOME%" >nul 2>&1
if "!ERRORLEVEL!"=="0" goto :inst_path_have
for /f "tokens=2,*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul ^| findstr /i "    Path"') do set "USRPATH=%%b"
if not defined USRPATH set "USRPATH="
setx PATH "!USRPATH!;%BARISTA_HOME%" >nul 2>&1
call :row ok "PATH" "added %BARISTA_HOME% (new terminals only)"
goto :inst_report
:inst_path_have
call :row ok "PATH" "already registered"
goto :inst_report
:inst_path_skip
call :row ok "PATH" "left untouched  (use --path to run 'arima' from anywhere)"

:inst_report
call :section "READINESS"
call :scan_deps
call :show_runtimes
if exist "%ARIMA_JAR%" call :row ok  "JAR" "%ARIMA_JAR%"
if not exist "%ARIMA_JAR%" call :row err "JAR" "not built"
echo.
if "!M_JAVA!"=="1" goto :inst_notready
if "!M_MVN!"=="1"  goto :inst_notready
if not exist "%ARIMA_JAR%" goto :inst_notready
call :coffee
if "!MISS_N!"=="0" goto :inst_allready
call :ok "  READY -- Arima will start. !MISS_N! optional runtime(s) absent:"
if "!M_GIT!"=="1"  call :dim "      Git       enables 'arima update'                https://git-scm.com/"
if "!M_NODE!"=="1" call :dim "      Node.js   JavaScript + TypeScript cells         https://nodejs.org/"
if "!M_NET!"=="1"  call :dim "      .NET      C# + F# cells                         https://dot.net/"
if "!M_PY!"=="1"   call :dim "      Python    Python cells + PyPI                   https://www.python.org/downloads/"
if "!M_CPP!"=="1"  call :dim "      C++       C++ cells                             https://visualstudio.microsoft.com/downloads/"
call :dim "      Add them later and re-run: arima install"
goto :inst_next
:inst_allready
call :ok "  EVERYTHING IS READY -- all eight languages are available."
:inst_next
echo.
echo     arima start        Start the server and open the UI
echo     arima start --bg   Start detached, logs to arima.log
echo     arima mcp tools    Drive Arima over MCP from this terminal
echo     arima update       Sync with upstream and rebuild
echo.
exit /b 0

:inst_notready
call :err "  NOT READY."
if "!M_JAVA!"=="1" call :dim "      Java      required -- https://adoptium.net/"
if "!M_MVN!"=="1"  call :dim "      Maven     required -- https://maven.apache.org/"
if not exist "%ARIMA_JAR%" call :dim "      JAR       not built -- run 'arima build' once the tools above exist"
call :dim "      Then re-run: arima install"
echo.
exit /b 1

REM Sets M_<TOOL>=1 for each missing dependency and MISS_N to the count.
:scan_deps
set "M_JAVA=0"
set "M_MVN=0"
set "M_GIT=0"
set "M_NODE=0"
set "M_NET=0"
set "M_PY=0"
set "M_CPP=0"
set /a MISS_N=0
call :java_probe
if !JAVA_MAJOR! lss %MIN_JAVA% set "M_JAVA=1" & set /a MISS_N+=1
call :have mvn
if not "!ERRORLEVEL!"=="0" set "M_MVN=1" & set /a MISS_N+=1
if "%F_SKIPOPT%"=="1" exit /b 0
call :have git
if not "!ERRORLEVEL!"=="0" set "M_GIT=1" & set /a MISS_N+=1
call :have node
if not "!ERRORLEVEL!"=="0" set "M_NODE=1" & set /a MISS_N+=1
call :have dotnet
if not "!ERRORLEVEL!"=="0" set "M_NET=1" & set /a MISS_N+=1
call :python_probe
if not defined PY_BIN set "M_PY=1" & set /a MISS_N+=1
call :cpp_probe
if not defined CXX_BIN set "M_CPP=1" & set /a MISS_N+=1
exit /b 0

:show_deps
call :show_runtimes
exit /b 0

:winget_install
call :row dots "%~1" "winget install --id %~2"
winget install --id %~2 --exact --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
call :refresh_path
call :row ok "%~1" "winget finished -- verify below"
exit /b 0

REM winget puts new tools on the persisted PATH, not this process's copy.
:refresh_path
for /f "tokens=2,*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul ^| findstr /i "    Path"') do set "SYSPATH=%%b"
for /f "tokens=2,*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul ^| findstr /i "    Path"') do set "USRPATH=%%b"
if defined SYSPATH set "PATH=!SYSPATH!"
if defined USRPATH set "PATH=!PATH!;!USRPATH!"
exit /b 0

:ensure_dir
if exist "%~1" call :row ok "%~1" "already present" & exit /b 0
mkdir "%~1" >nul 2>&1
call :row ok "%~1" "created"
exit /b 0


REM ============================================================================
REM  UPDATE  - sync with upstream, refusing to touch uncommitted work
REM ============================================================================
:cmd_update
set "BN_HEAD=A R I M A   -   U P D A T E"
set "BN_L2=Sync this checkout with upstream, then rebuild."
set "BN_L3=Your local commits are replayed on top (rebase)."
set "BN_L4=Uncommitted work always blocks the update."
call :banner

call :have git
if not "!ERRORLEVEL!"=="0" goto :upd_nogit
git rev-parse --is-inside-work-tree >nul 2>&1
if not "!ERRORLEVEL!"=="0" goto :upd_norepo

set "WAS_RUNNING=0"
call :server_up
if "!ERRORLEVEL!"=="0" set "WAS_RUNNING=1"

call :step 1 5 "Checking your working tree"
call :bar 10 "git status"
set /a DIRTY_N=0
for /f "tokens=*" %%l in ('git status --porcelain --untracked-files^=no 2^>nul') do set /a DIRTY_N+=1
if !DIRTY_N! gtr 0 goto :upd_dirty
call :row ok "Working tree" "clean -- safe to rebase"
set /a UNTRACKED_N=0
for /f "tokens=*" %%l in ('git ls-files --others --exclude-standard 2^>nul') do set /a UNTRACKED_N+=1
if !UNTRACKED_N! gtr 0 call :row warn "Untracked" "!UNTRACKED_N! new file(s) present -- they will be left alone"

call :step 2 5 "Fetching from upstream"
call :bar 30 "git fetch --prune"
set "HAS_ORIGIN=1"
git remote get-url origin >nul 2>&1
if not "!ERRORLEVEL!"=="0" set "HAS_ORIGIN=0"
if "!HAS_ORIGIN!"=="0" goto :upd_noremote
git fetch --prune origin
if not "!ERRORLEVEL!"=="0" goto :upd_fetchfail
call :row ok "Fetch" "up to date with origin"
goto :upd_sync
:upd_noremote
call :row warn "Remote" "no 'origin' configured -- skipping sync, rebuilding only"
set "REBASED=0"
goto :upd_build

:upd_sync
call :step 3 5 "Syncing with the base branch"
set "REBASED=0"
for /f "tokens=*" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "BRANCH=%%b"
set "BASE="
for /f "tokens=*" %%b in ('git rev-parse --abbrev-ref --symbolic-full-name "@{u}" 2^>nul') do set "BASE=%%b"
if not defined BASE set "BASE=origin/master"
git rev-parse --verify --quiet "!BASE!" >nul 2>&1
if not "!ERRORLEVEL!"=="0" set "BASE=origin/main"
git rev-parse --verify --quiet "!BASE!" >nul 2>&1
if not "!ERRORLEVEL!"=="0" goto :upd_nobase
call :row ok "Branch" "!BRANCH!  ->  !BASE!"

set "AHEAD=0"
set "BEHIND=0"
for /f "tokens=1,2" %%a in ('git rev-list --left-right --count "HEAD...!BASE!" 2^>nul') do set "AHEAD=%%a" & set "BEHIND=%%b"
call :row ok "Divergence" "!AHEAD! local commit(s) ahead, !BEHIND! behind"

if "!BEHIND!"=="0" goto :upd_insync
if "!AHEAD!"=="0"  goto :upd_ff
call :bar 50 "rebasing !AHEAD! local commit(s) onto !BASE!"
git rebase "!BASE!"
if not "!ERRORLEVEL!"=="0" goto :upd_conflict
call :row ok "Sync" "rebased !AHEAD! commit(s) onto !BASE!"
set "REBASED=1"
goto :upd_build

:upd_ff
call :bar 50 "fast-forwarding !BEHIND! commit(s)"
git merge --ff-only "!BASE!"
if not "!ERRORLEVEL!"=="0" goto :upd_fffail
call :row ok "Sync" "fast-forwarded !BEHIND! commit(s)"
set "REBASED=1"
goto :upd_build

:upd_insync
call :bar 60 "already in sync"
call :row ok "Sync" "nothing to pull -- already current"

:upd_build
if "%F_NOBUILD%"=="1" goto :upd_nobuild
if "!REBASED!"=="0" if exist "%ARIMA_JAR%" goto :upd_nojar
call :step 4 5 "Rebuilding the JAR"
call :have mvn
if not "!ERRORLEVEL!"=="0" goto :upd_nomvn
call :bar 80 "mvn clean package -DskipTests"
echo.
call mvn clean package -DskipTests
if not "!ERRORLEVEL!"=="0" goto :upd_buildfail
call :bar 100 "build complete"
call :row ok "JAR" "%ARIMA_JAR%"
goto :upd_finish
:upd_nobuild
call :step 4 5 "Build skipped (--no-build)"
call :bar 100 "skipped"
goto :upd_finish
:upd_nojar
call :step 4 5 "Rebuild not needed"
call :bar 100 "JAR already current"
call :row ok "JAR" "unchanged -- no new commits to compile"

:upd_finish
call :step 5 5 "Finishing up"
if "!WAS_RUNNING!"=="1" call :row warn "Server" "still running the OLD jar -- restart to pick up the update"
if "!WAS_RUNNING!"=="1" call :dim  "           arima restart"
if "!WAS_RUNNING!"=="0" call :row ok "Server" "not running -- start it with: arima start"
call :section "UPDATED"
call :ok "  Arima Notebooks is up to date."
echo.
exit /b 0

:upd_dirty
echo.
call :section "UNCOMMITTED CHANGES -- UPDATE STOPPED"
call :err "  You have local edits that a rebase could overwrite."
call :dim "  Nothing has been changed. Commit (or stash) them first so your work is safe:"
echo.
for /f "tokens=*" %%l in ('git status --porcelain --untracked-files^=no 2^>nul') do call :warn "      %%l"
echo.
call :info "  Option A -- keep the changes as a commit (recommended)"
call :dim  "      git add -A"
echo %C_DIM%      git commit -m "wip: my local changes"%C_RST%
call :dim  "      arima update"
echo.
call :info "  Option B -- park the changes, update, then bring them back"
echo %C_DIM%      git stash push -u -m "before arima update"%C_RST%
call :dim  "      arima update"
call :dim  "      git stash pop"
echo.
exit /b 2

:upd_conflict
set "CONFLICTS="
for /f "tokens=*" %%f in ('git diff --name-only --diff-filter^=U 2^>nul') do set "CONFLICTS=!CONFLICTS! %%f"
git rebase --abort >nul 2>&1
echo.
call :section "MERGE CONFLICT -- UPDATE ROLLED BACK"
call :err "  The rebase hit conflicts, so it was aborted."
call :dim "  Your branch is back exactly where it was -- nothing was lost."
if defined CONFLICTS echo.
if defined CONFLICTS call :info "  Files that conflict with upstream:"
if defined CONFLICTS for %%f in (!CONFLICTS!) do call :warn "      %%f"
echo.
call :info "  Resolve it by hand, once:"
call :dim  "      git rebase !BASE!"
call :dim  "      (fix the conflicted files, then)"
call :dim  "      git add <file>"
call :dim  "      git rebase --continue"
call :dim  "      arima build"
echo.
call :info "  Or drop your local commits and take upstream as-is (destructive):"
call :dim  "      git reset --hard !BASE!"
echo.
exit /b 3

:upd_fffail
call :row err "Sync" "fast-forward failed -- run 'git status' to inspect"
exit /b 3
:upd_fetchfail
call :row err "Fetch" "failed -- check your network / credentials"
exit /b 1
:upd_nobase
call :row err "Base" "cannot resolve a base branch (tried origin/master and origin/main)"
exit /b 1
:upd_nomvn
call :row err "Maven" "not found -- install from https://maven.apache.org/"
exit /b 1
:upd_buildfail
echo.
call :err "  Build failed after the update -- see the Maven output above."
exit /b 1
:upd_nogit
call :err "  ERROR: git not found."
call :dim "  Install git from https://git-scm.com/ then re-run: arima update"
exit /b 1
:upd_norepo
call :err "  ERROR: this folder is not a git repository."
call :dim "  Re-clone with: git clone https://github.com/snchande/arima-notebooks.git"
exit /b 1


REM ============================================================================
REM  UNINSTALL  - remove build output; notebooks and source are never touched
REM ============================================================================
:cmd_uninstall
set "BN_HEAD=A R I M A   -   U N I N S T A L L"
set "BN_L2=Remove build output and runtime logs."
set "BN_L3=Your notebooks and source code are never touched."
set "BN_L4="
call :banner

set /a RM_N=0
call :section "WILL BE REMOVED"
if exist "target"        call :row warn "target/"       "compiled classes + the JAR" & set /a RM_N+=1
if exist "arima.log"     call :row warn "arima.log"     "background stdout log" & set /a RM_N+=1
if exist "arima-err.log" call :row warn "arima-err.log" "background stderr log" & set /a RM_N+=1
if "%F_PURGE%"=="1" if exist "data" call :row err "data/" "SETTINGS, packages, users -- unrecoverable" & set /a RM_N+=1
if !RM_N!==0 call :dim "    (nothing -- already clean)"

call :section "WILL BE KEPT"
call :row ok "notebooks/"  "every notebook you wrote"
call :row ok "src/, docs/" "source code and documentation"
call :row ok ".git/"       "git history and remotes"
if not "%F_PURGE%"=="1" call :row ok "data/" "settings, packages, users  (use --purge to delete)"

if !RM_N!==0 goto :uninst_nothing
call :confirm "Remove the items listed above?"
if not "!ERRORLEVEL!"=="0" goto :uninst_cancel

echo.
call :step 1 3 "Stopping the server"
call :server_up
if "!ERRORLEVEL!"=="0" goto :uninst_kill
call :row ok "Server" "not running"
goto :uninst_rm
:uninst_kill
call :get_pid
if defined SRV_PID taskkill /PID !SRV_PID! /F >nul 2>&1
call :row ok "Server" "stopped"

:uninst_rm
call :step 2 3 "Removing files"
if exist "target"        call :bar 33  "removing target/"       & rmdir /s /q "target"        & call :row ok "target/" "removed"
if exist "arima.log"     call :bar 60  "removing arima.log"     & del /q "arima.log"          & call :row ok "arima.log" "removed"
if exist "arima-err.log" call :bar 80  "removing arima-err.log" & del /q "arima-err.log"      & call :row ok "arima-err.log" "removed"
if "%F_PURGE%"=="1" if exist "data" call :bar 100 "removing data/" & rmdir /s /q "data" & call :row err "data/" "removed"
call :bar 100 "done"

call :step 3 3 "Cleaning up the PATH entry"
if not "%F_PATH%"=="1" goto :uninst_path_skip
call :row warn "PATH" "remove '%BARISTA_HOME%' from your user PATH in System Properties"
goto :uninst_done
:uninst_path_skip
call :row ok "PATH" "left untouched  (use --path to be reminded about the entry)"

:uninst_done
call :section "UNINSTALLED"
call :ok  "  Build output removed. The checkout itself is intact."
call :dim "  Reinstall any time with:  arima install"
echo.
exit /b 0

:uninst_nothing
echo.
call :ok "  Nothing to do."
echo.
exit /b 0

:uninst_cancel
echo.
call :warn "  Cancelled -- nothing was removed."
echo.
exit /b 1


REM ============================================================================
REM  SERVER
REM ============================================================================
:cmd_brew
call :coffee
call :banner
exit /b 0

:cmd_start
call :coffee
call :banner

call :server_up
if "!ERRORLEVEL!"=="0" goto :start_already

call :section "ENVIRONMENT"
call :java_probe
if "!JAVA_MAJOR!"=="0" goto :start_nojava
call :show_runtimes
call :show_copilots

call :ensure_jar
if not "!ERRORLEVEL!"=="0" exit /b 1

if "%F_BG%"=="1" goto :start_bg

call :section "RUNNING (FOREGROUND)"
call :dim "  Press Ctrl+C to stop"
call :dim "  %RULE%"
echo.
start "" cmd /c "ping -n 5 127.0.0.1 >nul && start %ARIMA_URL%"

:start_loop
java --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED -jar "%ARIMA_JAR%"
REM Exit code 42 = restart requested by the UI (matches scripts/start.bat)
if not "!ERRORLEVEL!"=="42" exit /b !ERRORLEVEL!
echo.
call :dim  "  %RULE%"
call :info "  Restarting Arima Notebooks..."
call :dim  "  %RULE%"
ping -n 2 127.0.0.1 >nul
goto :start_loop

:start_bg
call :section "STARTING (BACKGROUND)"
start /b "" java --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED -jar "%ARIMA_JAR%" > arima.log 2>&1
call :row ok "Process" "detached -- logs: arima.log"
call :wait_server
if not "!ERRORLEVEL!"=="0" goto :start_bg_slow
call :get_pid
if defined SRV_PID call :row ok "PID" "!SRV_PID!"
call :row ok "Server" "up at %ARIMA_URL%"
start "" "%ARIMA_URL%"
echo.
exit /b 0
:start_bg_slow
call :row err "Server" "no response after 45s -- check arima.log"
exit /b 1

:start_already
call :ok   "  Arima Notebooks is already running."
call :info "  URL: %ARIMA_URL%"
echo.
set "OPEN="
set /p "OPEN=  Open in browser? [Y/n]: "
if /i not "!OPEN!"=="n" start "" "%ARIMA_URL%"
exit /b 0

:start_nojava
call :err "  ERROR: Java not found."
call :dim "  Install JDK %MIN_JAVA%+ (21 recommended) from https://adoptium.net/"
call :dim "  Or let Arima do it for you:  arima install"
exit /b 1

:cmd_stop
set "BN_HEAD=A R I M A   -   S T O P"
call :banner
call :get_pid
if not defined SRV_PID goto :stop_none
call :row ok "Server" "stopping PID !SRV_PID!"
taskkill /PID !SRV_PID! /F >nul 2>&1
call :row ok "Server" "stopped"
echo.
exit /b 0
:stop_none
call :row warn "Server" "not running"
echo.
exit /b 1

:cmd_restart
set "BN_HEAD=A R I M A   -   R E S T A R T"
call :banner
call :step 1 2 "Stopping"
call :get_pid
if defined SRV_PID taskkill /PID !SRV_PID! /F >nul 2>&1
if defined SRV_PID call :row ok "Server" "stopped PID !SRV_PID!"
if not defined SRV_PID call :row ok "Server" "was not running"
ping -n 2 127.0.0.1 >nul
call :step 2 2 "Starting"
goto :cmd_start

:cmd_status
set "BN_HEAD=A R I M A   -   S T A T U S"
call :banner
call :section "SERVER"
call :server_up
if not "!ERRORLEVEL!"=="0" goto :status_down
call :show_live_server
goto :status_jar
:status_down
call :row warn "State" "STOPPED -- start it with: arima start"
:status_jar
if exist "%ARIMA_JAR%"     call :row ok   "JAR" "%ARIMA_JAR%"
if not exist "%ARIMA_JAR%" call :row warn "JAR" "not built yet -- run: arima install"

call :section "RUNTIMES"
call :show_runtimes

call :section "AI"
call :show_copilots

call :have git
if not "!ERRORLEVEL!"=="0" goto :status_end
git rev-parse --is-inside-work-tree >nul 2>&1
if not "!ERRORLEVEL!"=="0" goto :status_end
call :section "CHECKOUT"
for /f "tokens=*" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "BRANCH=%%b"
for /f "tokens=*" %%s in ('git rev-parse --short HEAD 2^>nul') do set "SHA=%%s"
call :row ok "Branch" "!BRANCH! @ !SHA!"
set /a DIRTY_N=0
for /f "tokens=*" %%l in ('git status --porcelain --untracked-files^=no 2^>nul') do set /a DIRTY_N+=1
if !DIRTY_N! gtr 0 call :row warn "Changes" "!DIRTY_N! uncommitted file(s) -- commit before: arima update"
if !DIRTY_N!==0 call :row ok "Changes" "clean -- 'arima update' is safe to run"
:status_end
echo.
exit /b 0

:cmd_open
call :server_up
if not "!ERRORLEVEL!"=="0" goto :open_down
call :ok "  Opening Arima Notebooks..."
start "" "%ARIMA_URL%"
exit /b 0
:open_down
call :err "  Arima Notebooks is not running. Start it first: arima start"
exit /b 1

:cmd_logs
if not exist "arima.log" goto :logs_none
call :info "  Tailing arima.log (Ctrl+C to stop)..."
call :dim  "  %RULE%"
powershell -NoProfile -Command "Get-Content -Path 'arima.log' -Wait -Tail 40"
exit /b 0
:logs_none
call :err "  No log file found (arima.log)."
call :dim "  Logs are only written in background mode: arima start --bg"
exit /b 1


REM ============================================================================
REM  BUILD
REM ============================================================================
:cmd_build
set "BN_HEAD=A R I M A   -   B U I L D"
call :banner
call :have mvn
if not "!ERRORLEVEL!"=="0" goto :build_nomvn
call :step 1 1 "mvn clean package -DskipTests"
echo.
call mvn clean package -DskipTests
if not "!ERRORLEVEL!"=="0" goto :build_fail
call :section "BUILD SUCCESSFUL"
call :row ok "JAR" "%ARIMA_JAR%"
call :dim "  Run: arima start"
echo.
exit /b 0
:build_fail
call :section "BUILD FAILED"
call :err "  Check the Maven output above."
echo.
exit /b 1
:build_nomvn
call :err "  ERROR: Maven not found."
call :dim "  Install from https://maven.apache.org/ or run: arima install"
exit /b 1


REM ============================================================================
REM  MCP  - drive Arima over JSON-RPC from the command line
REM
REM  POST /api/mcp/messages is a stateless JSON-RPC 2.0 endpoint. Batch cannot
REM  build or parse JSON, so this subcommand forwards to arima.ps1, which owns
REM  the single MCP implementation. Output is byte-identical in both shells.
REM ============================================================================
:cmd_mcp
REM Forward the RAW remainder of the command line rather than rebuilding it from
REM %1..%n. Two cmd.exe quirks make the rebuild approach wrong:
REM   1. cmd splits batch arguments on '=' as well as spaces, so `key=value`
REM      would arrive as two separate tokens.
REM   2. `shift` also shifts %0, which silently corrupts %~dp0 afterwards --
REM      that is why the script path below comes from BARISTA_HOME instead.
REM `for /f` splits on whitespace only, so tokens=1,* drops the literal "mcp"
REM and hands back everything after it exactly as the user typed it.
set "RAWARGS=%*"
set "MCPARGS="
if defined RAWARGS for /f "tokens=1,*" %%a in ("!RAWARGS!") do set "MCPARGS=%%b"
where powershell >nul 2>&1
if not "!ERRORLEVEL!"=="0" goto :mcp_nops
powershell -NoProfile -ExecutionPolicy Bypass -File "%BARISTA_HOME%\arima.ps1" mcp !MCPARGS!
exit /b !ERRORLEVEL!
:mcp_nops
call :err "  ERROR: powershell.exe not found -- 'arima mcp' needs it to speak JSON-RPC."
call :dim "  You can still call MCP directly with curl:"
echo %C_DIM%      curl -s -X POST %ARIMA_MCP% -H "Content-Type: application/json" -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"%C_RST%
exit /b 1


REM ============================================================================
REM  HOME (bare invocation) + LIVE SERVER
REM ============================================================================

REM Render the live-server rows. arima.ps1 owns the /api/system/info mapping so
REM there is exactly one copy of it; this just paints what it prints.
:show_live_server
set "LIVE_OK=0"
where powershell >nul 2>&1
if not "!ERRORLEVEL!"=="0" goto :live_fallback
for /f "usebackq tokens=1,2,* delims=|" %%a in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%BARISTA_HOME%\arima.ps1" _inforows 2^>nul`) do (
    call :row %%a "%%b" "%%c"
    set "LIVE_OK=1"
)
if "!LIVE_OK!"=="1" exit /b 0
:live_fallback
REM The port answers but /api/system/info did not: an older build, or oauth
REM mode where /api/** requires a signed-in session.
call :get_pid
call :row ok "Status" "RUNNING at %ARIMA_URL%"
if defined SRV_PID call :row ok "PID" "!SRV_PID!"
call :row warn "Details" "live metadata unavailable -- rebuild, or sign in if auth mode is oauth"
exit /b 0

:show_commands
call :section "ALL COMMANDS"
echo     %C_WHT%Lifecycle  %C_RST%%C_DIM%install   update   uninstall%C_RST%
echo     %C_WHT%Server     %C_RST%%C_DIM%start   stop   restart   status   open   logs%C_RST%
echo     %C_WHT%MCP        %C_RST%%C_DIM%mcp   mcp tools   mcp call   mcp exec   mcp config%C_RST%
echo     %C_WHT%Build      %C_RST%%C_DIM%build   rebuild%C_RST%
echo     %C_WHT%Info       %C_RST%%C_DIM%version   welcome   docs   agents   brew   help%C_RST%
echo     %C_WHT%Flags      %C_RST%%C_DIM%--bg  --yes  --purge  --no-build  --path  --skip-optional  --no-anim%C_RST%
echo.
call :dim "    arima help    full description of every command and flag"
exit /b 0

REM First run: nothing is built yet, so explain what Arima is and offer the one
REM command that gets the user to a running server.
:show_fre
call :section "FIRST RUN"
call :dim "    Arima Notebooks is not built on this machine yet."
echo.
echo     %C_WHT%What it is%C_RST%
call :dim "      A notebook you run on your own machine. Write cells in Java/JShell,"
call :dim "      JavaScript, TypeScript, C#, F#, C++ or Python; chain them into"
call :dim "      pipelines with //@ anchor and //@ depends; and drive the whole thing"
call :dim "      from any MCP client or an AI CLI."
call :dim "      Local-first: no account, no telemetry, nothing leaves your machine."

call :section "READINESS"
call :show_runtimes
call :row err "Build" "%ARIMA_JAR% not built yet"

call :section "NEXT"
call :dim "    One command checks every dependency, installs what is missing, and builds:"
echo.
echo         %C_WHT%arima install%C_RST%
call :dim "        arima start        then start the server and open the UI"
echo.
if "%F_YES%"=="1" goto :fre_go
REM Batch has no isatty(), and `set /p` returns EMPTY (not blocking) when stdin
REM is redirected -- which would read as the default "yes" and kick off an
REM unattended install. Detect `cmd /c ...` invocations via CMDCMDLINE and only
REM print guidance in that case. Substring replacement is used instead of
REM `echo | find` so nothing in the command line gets re-parsed.
set "CCL=!CMDCMDLINE!"
set "CCL_STRIPPED=!CCL: /c =!"
if not "!CCL_STRIPPED!"=="!CCL!" goto :fre_noask
set "FRE_ANS="
set /p "FRE_ANS=%C_YEL%  Install everything that is missing and start Arima now? %C_DIM%[Y/n]: %C_RST%"
if not defined FRE_ANS goto :fre_go
if /i "!FRE_ANS!"=="y"   goto :fre_go
if /i "!FRE_ANS!"=="yes" goto :fre_go
echo.
call :dim "    Nothing installed. Run  arima install  when you are ready."
echo.
exit /b 0
:fre_noask
echo.
call :dim "    Run  arima install  to install what is missing, then  arima start."
echo.
exit /b 0
:fre_go
echo.
call :cmd_install
if not "!ERRORLEVEL!"=="0" exit /b !ERRORLEVEL!
echo.
call :cmd_start
exit /b !ERRORLEVEL!

:cmd_home
call :banner
echo %C_CYN%  %ARIMA_TAGLINE%%C_RST%

call :server_up
if not "!ERRORLEVEL!"=="0" goto :home_down
call :section "LIVE SERVER"
call :show_live_server
call :show_commands
echo.
call :info "  Open it: %ARIMA_URL%"
echo.
exit /b 0

:home_down
if not exist "%ARIMA_JAR%" goto :show_fre
call :section "SERVER"
call :row warn "State" "STOPPED"
call :row ok   "Build" "%ARIMA_JAR%"
call :show_commands
call :section "NEXT"
echo     %C_WHT%arima start%C_RST%%C_DIM%        start the server and open %ARIMA_URL%%C_RST%
call :dim "    arima start --bg   start it detached, logging to arima.log"
echo.
exit /b 0

REM ============================================================================
REM  INFO
REM ============================================================================
:cmd_version
set "BN_HEAD=A R I M A   -   V E R S I O N"
call :banner
call :section "VERSIONS"
REM Read the version off the JAR name rather than parsing pom.xml: it is the
REM artifact we actually launch, and batch cannot reliably grep for <version>.
set "PROJ_VER=%ARIMA_JAR%"
set "PROJ_VER=!PROJ_VER:target\arima-notebooks-=!"
set "PROJ_VER=!PROJ_VER:.jar=!"
call :row ok "Arima" "!PROJ_VER!"
:ver_runtimes
call :show_runtimes
echo.
exit /b 0

:cmd_welcome
call :detect_copilots
call :coffee
call :banner
call :title_ "  Welcome to Arima Notebooks"
call :dim    "  A local notebook for Java, JShell, JS, TS, C#, F#, C++ and Python"
call :dim    "  -- with AI co-pilots and a built-in MCP server."

call :section "PICK HOW YOU WANT TO WORK"
echo     %C_WHT%1^) Open the UI            %C_RST%%C_DIM%full notebook experience in your browser%C_RST%
call :dim "         arima start          ->  %ARIMA_URL%"
echo     %C_WHT%2^) Drive Arima over MCP   %C_RST%%C_DIM%operate ^& automate from any MCP client%C_RST%
call :dim "         arima mcp tools      list the tools right here in the terminal"
call :dim "         SSE  %ARIMA_URL%/api/mcp/sse"
call :dim "         POST %ARIMA_MCP%"
echo     %C_WHT%3^) Personalize ^& extend   %C_RST%%C_DIM%add features -- needs an agentic CLI%C_RST%
call :dim "         run  claude  /  copilot  /  agy   in this folder, then ask the arima agent"

call :section "AI CO-PILOTS"
call :show_copilots

call :section "THE ONE DIFFERENCE"
call :dim "    This arima CLI operates ^& automates Arima (incl. MCP) but cannot change its code."
call :dim "    An agentic CLI (claude / copilot / agy) can ALSO personalize and extend Arima."

call :section "NEXT"
echo     arima install    Check prerequisites, install what is missing, build
echo     arima start      Start the server and open the UI
echo     arima docs       Open the brochure and list the docs
echo     arima agents     AI co-pilots, skills ^& the arima agent
echo.
call :dim "  Full welcome: docs\WELCOME.md"
echo.
exit /b 0

:cmd_docs
set "BN_HEAD=A R I M A   -   D O C S"
call :banner
call :section "DOCUMENTATION"
echo     Brochure ^(PDF^)   docs\brochure\arima-brochure.pdf
echo     Welcome          docs\WELCOME.md
echo     Getting started  README.md
echo     Architecture     docs\ARCHITECTURE.md
echo     API + MCP        docs\API.md
echo     Contributor      CONTRIBUTING.md  +  AGENTS.md
echo     Cheat sheet      docs\cheatsheet.html
echo.
if not exist "docs\brochure\arima-brochure.pdf" goto :docs_nopdf
call :ok "    Opening the brochure..."
start "" "docs\brochure\arima-brochure.pdf"
echo.
exit /b 0
:docs_nopdf
call :warn "    Brochure PDF not found -- open docs\brochure\arima-brochure.html in a browser."
echo.
exit /b 0

:cmd_agents
set "BN_HEAD=A R I M A   -   A G E N T S"
set "BN_L2=AI co-pilots, guardrails, skills and subagents"
set "BN_L3=wired into this repository."
set "BN_L4="
call :banner

call :section "DETECTED AI CLIs"
call :detect_copilots
if not defined BARISTA_AI_COPILOTS goto :agents_nocli
call :have claude
if "!ERRORLEVEL!"=="0" call :row ok "Claude" "binary: claude"
call :have copilot
if "!ERRORLEVEL!"=="0" call :row ok "Copilot" "binary: copilot"
call :have agy
if "!ERRORLEVEL!"=="0" call :row ok "Antigravity" "binary: agy"
call :have gh
if "!ERRORLEVEL!"=="0" call :row dots "gh" "GitHub CLI -- use: gh copilot"
goto :agents_guardrails
:agents_nocli
call :row warn "none" "install one of the following:"
call :dim "      Claude     :  https://claude.ai/code                      then  claude auth"
call :dim "      Copilot    :  GitHub Copilot CLI (copilot, v1.0.55-5+; used by the Copilot SDK)"
call :dim "      Antigravity:  https://antigravity.google/docs/cli-install  then  run  agy"

:agents_guardrails
call :section "GUARDRAILS (read automatically by every AI CLI in this repo)"
call :guard "AGENTS.md"
call :guard ".claude\skills"
call :guard ".claude\agents"
call :guard "CLAUDE.md"
call :guard ".github\copilot-instructions.md"
call :guard "GEMINI.md"

call :section "SKILLS (auto-invoke when your request matches)"
if exist ".claude\skills" for /d %%d in (".claude\skills\*") do call :dim "      %%~nxd"

call :section "SUBAGENTS (spawn explicitly for a focused review)"
if exist ".claude\agents" for %%f in (".claude\agents\*.md") do call :dim "      %%~nf"

call :section "HOW TO USE"
echo     In the Arima UI : open the AI panel ^(Ctrl+\^), pick a provider, ask away.
echo     In a terminal   : run your CLI from this folder so it loads AGENTS.md:
call :dim "                        claude        (or)   copilot        (or)   agy"
echo     Example prompt  : "Add a Kotlin execution mode following CppExecutionService,
call :dim "                       then run pwsh .\scripts\security-check.ps1 and open a PR."
echo.
call :dim "    Env exported for this session:"
call :dim "      BARISTA_HOME=%BARISTA_HOME%"
call :dim "      BARISTA_AGENTS_GUIDE=%BARISTA_AGENTS_GUIDE%"
call :dim "      BARISTA_SKILLS_DIR=%BARISTA_SKILLS_DIR%"
call :dim "      BARISTA_AGENTS_DIR=%BARISTA_AGENTS_DIR%"
echo.
exit /b 0

:guard
if exist "%~1" call :row ok "%~1" "" & exit /b 0
call :row warn "%~1" "missing"
exit /b 0

:cmd_help
set "BN_HEAD=A R I M A   N O T E B O O K S   C L I"
set "BN_L3=Usage:  arima [command] [flags]"
call :banner

call :section "START HERE"
echo     ^(no command^)     Home screen -- live server metadata when it is running,
call :dim "                     the full command list, or first-run setup if not built yet"

call :section "LIFECYCLE"
echo     install          Check every dependency, install what is missing, build, report readiness
echo     update           Sync with upstream ^(fast-forward or rebase^), then rebuild
echo     uninstall        Remove build output and logs ^(notebooks are kept^)

call :section "SERVER"
echo     start            Start the server, auto-build if needed, open the browser
echo     start --bg       Start detached; logs to arima.log
echo     stop             Stop the running server
echo     restart          Stop then start ^(use after 'update'^)
echo     status           Server state, PID, runtimes, AI CLIs, checkout state
echo     open             Open the browser ^(server must already be running^)
echo     logs             Tail arima.log ^(background mode only^)

call :section "MCP (drive Arima from this terminal)"
echo     mcp              Endpoints, live server info, and the command list
echo     mcp tools        List every MCP tool and its parameters
echo     mcp call ^<tool^> k=v ...   Call any tool by name
echo     mcp exec ^<code^>          Run Java/JShell code through MCP
echo     mcp notebooks ^| read ^<id^> ^| search ^<q^> ^| agents ^| run-agent ^<id^> ^<task^>
echo     mcp config       Print an MCP client config snippet

call :section "BUILD"
echo     build            mvn clean package -DskipTests
echo     rebuild          Alias of build ^(always a clean build^)

call :section "INFO"
echo     version          Arima version plus every detected runtime
echo     welcome          Pick how you want to work: UI, MCP, or extend
echo     docs             Open the brochure and list the documentation
echo     agents           AI co-pilots, guardrails, skills ^& subagents
echo     brew             Watch Barista serve a coffee bean ^(alias: coffee^)
echo     help             Show this help

call :section "FLAGS"
echo     --bg             start detached, logging to arima.log
echo     --yes            skip confirmation prompts ^(install / uninstall^)
echo     --purge          uninstall: also delete data\ ^(settings, packages, users^)
echo     --no-build       install/update: skip the Maven build
echo     --path           install/uninstall: add or remove this folder on the user PATH
echo     --skip-optional  install: only install the required tools ^(Java + Maven^)
echo     --no-anim        disable the animated banner and spinners

call :section "EXAMPLES"
call :dim "    arima                          home screen: live status + every command"
call :dim "    arima install --yes            install every missing dependency, no prompts"
call :dim "    arima install --path           first-time setup, then 'arima' from anywhere"
call :dim "    arima update                   pull upstream, rebase local commits, rebuild"
call :dim "    arima restart                  stop, then start again"
echo %C_DIM%    arima mcp exec "2+2"           run a snippet through the MCP server%C_RST%
call :dim "    arima uninstall --purge --yes  full clean, no prompts"
echo.
call :info "  URL: %ARIMA_URL%"
call :dim  "  No colour? set ARIMA_NO_COLOR=1"
echo.
exit /b 0
