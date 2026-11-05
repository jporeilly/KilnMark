@echo off
rem Starts Kilnmark, and Keycloak too when .env says COMPOSE_PROFILES=auth, waits until it answers,
rem then opens it in the browser. Double-click it in the Kilnmark folder, or use the Kilnmark
rem shortcut that create-shortcut.ps1 makes. Needs Podman (with podman compose) or Docker.
setlocal EnableDelayedExpansion
cd /d "%~dp0"

if not exist ".env" (
  echo There is no .env in %CD%.
  echo Copy .env.example to .env and fill it in first: INSTALL.md, "A. Install a release".
  goto failed
)

rem Where browsers reach Kilnmark and Keycloak, as .env says.
set "KM_PORT=8780"
set "KM_URL="
set "KC_PORT=8180"
set "KC_URL="
set "KM_PROFILES="
for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do (
  if "%%a"=="KILNMARK_PORT" set "KM_PORT=%%b"
  if "%%a"=="KILNMARK_URL" set "KM_URL=%%b"
  if "%%a"=="KILNMARK_KEYCLOAK_PORT" set "KC_PORT=%%b"
  if "%%a"=="KILNMARK_KEYCLOAK_URL" set "KC_URL=%%b"
  if "%%a"=="COMPOSE_PROFILES" set "KM_PROFILES=%%b"
)
if not defined KM_URL set "KM_URL=http://localhost:%KM_PORT%"
if not defined KC_URL set "KC_URL=http://localhost:%KC_PORT%"

rem Podman first, then Docker.
set "COMPOSE="
where podman >nul 2>&1 && set "COMPOSE=podman compose"
if not defined COMPOSE where docker >nul 2>&1 && set "COMPOSE=docker compose"
if not defined COMPOSE (
  echo Neither Podman nor Docker was found. Install one of them first: INSTALL.md, "What to install first".
  goto failed
)
if "%COMPOSE%"=="podman compose" (
  podman info >nul 2>&1
  if errorlevel 1 (
    echo Starting the Podman machine...
    podman machine start
    if errorlevel 1 goto failed
  )
)

echo Starting Kilnmark...
%COMPOSE% up -d
if errorlevel 1 (
  echo Kilnmark could not start. See what went wrong with: %COMPOSE% logs
  goto failed
)

echo Waiting for Kilnmark at %KM_URL% ...
set /a tries=0
:wait_kilnmark
curl.exe -k -L -s -f -o nul "%KM_URL%/q/health/ready" && goto kilnmark_ready
set /a tries+=1
if !tries! geq 90 (
  echo Kilnmark did not answer within 3 minutes. See why with: %COMPOSE% logs kilnmark
  goto failed
)
rem Two seconds; timeout.exe refuses to wait when started without a console of its own.
ping -n 3 127.0.0.1 >nul
goto wait_kilnmark
:kilnmark_ready

echo !KM_PROFILES! | find "auth" >nul
if not errorlevel 1 (
  echo Waiting for Keycloak at %KC_URL% ...
  set /a tries=0
  goto wait_keycloak
)
goto open

:wait_keycloak
curl.exe -k -L -s -f -o nul "%KC_URL%/realms/kilnmark" && goto open
set /a tries+=1
if !tries! geq 150 (
  echo Keycloak did not answer within 5 minutes. See why with: %COMPOSE% logs keycloak keycloak-setup
  goto failed
)
ping -n 3 127.0.0.1 >nul
goto wait_keycloak

:open
echo Kilnmark is ready at %KM_URL%
start "" "%KM_URL%"
exit /b 0

:failed
echo.
pause
exit /b 1
