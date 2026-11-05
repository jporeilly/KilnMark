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

rem The settings the start needs, as .env says: spaces around = and quotes around a value are
rem allowed, and anything after the value on its line is ignored.
set "KM_PORT=8780"
set "KM_HTTPS_PORT=8443"
set "KM_TLS="
set "KM_URL="
set "KC_PORT=8180"
set "KM_PROFILES=none"
for /f "usebackq eol=# tokens=1,2 delims== " %%a in (".env") do (
  if "%%a"=="KILNMARK_PORT" set "KM_PORT=%%~b"
  if "%%a"=="KILNMARK_HTTPS_PORT" set "KM_HTTPS_PORT=%%~b"
  if "%%a"=="KILNMARK_TLS_CERT_FILE" set "KM_TLS=%%~b"
  if "%%a"=="KILNMARK_URL" set "KM_URL=%%~b"
  if "%%a"=="KILNMARK_KEYCLOAK_PORT" set "KC_PORT=%%~b"
  if "%%a"=="COMPOSE_PROFILES" set "KM_PROFILES=%%~b"
)
rem Where to open it: KILNMARK_URL without a slash at the end, or this machine's port.
if not defined KM_URL set "KM_URL=http://localhost:%KM_PORT%"
if "%KM_URL:~-1%"=="/" set "KM_URL=%KM_URL:~0,-1%"
rem Where to ask whether it is ready: on this machine, never through a proxy. With a certificate
rem in .env, Kilnmark may refuse plain HTTP from here, so ask over HTTPS.
set "KM_LOCAL=http://localhost:%KM_PORT%"
if defined KM_TLS set "KM_LOCAL=https://localhost:%KM_HTTPS_PORT%"
set "KC_LOCAL=http://localhost:%KC_PORT%"
set "KM_AUTH="
echo ,%KM_PROFILES: =%, | findstr /c:",auth," >nul && set "KM_AUTH=1"
rem Some compose tools read COMPOSE_PROFILES from .env and some do not: name the profile too.
set "KM_PROFILE_ARG="
if defined KM_AUTH set "KM_PROFILE_ARG=--profile auth"

rem Podman with its compose command first (starting its machine if need be), then Docker.
set "COMPOSE="
set "KM_NO_MACHINE="
where podman >nul 2>&1
if not errorlevel 1 (
  podman info >nul 2>&1
  if errorlevel 1 (
    echo Starting the Podman machine...
    podman machine start
    if errorlevel 1 set "KM_NO_MACHINE=1"
  )
  if not defined KM_NO_MACHINE podman compose version >nul 2>&1 && set "COMPOSE=podman compose"
)
if not defined COMPOSE docker compose version >nul 2>&1 && set "COMPOSE=docker compose"
if not defined COMPOSE (
  if defined KM_NO_MACHINE echo The Podman machine could not start. If Podman has none yet, make it with: podman machine init
  echo Neither podman compose nor docker compose could be used. Install one first: INSTALL.md, "What to install first".
  goto failed
)

echo Starting Kilnmark...
%COMPOSE% %KM_PROFILE_ARG% up -d
if errorlevel 1 (
  echo Kilnmark could not start. See what went wrong with: %COMPOSE% logs
  goto failed
)

echo Waiting for Kilnmark ...
set /a tries=0
:wait_kilnmark
curl.exe -k -L -s -f -o nul "%KM_LOCAL%/q/health/ready" && goto kilnmark_ready
set /a tries+=1
if !tries! geq 90 (
  echo Kilnmark did not answer within 3 minutes. See why with: %COMPOSE% logs kilnmark
  goto failed
)
rem Two seconds; timeout.exe refuses to wait when started without a console of its own.
ping -n 3 127.0.0.1 >nul
goto wait_kilnmark
:kilnmark_ready

if not defined KM_AUTH goto open
echo Waiting for Keycloak ...
set /a tries=0
:wait_keycloak
curl.exe -k -L -s -f -o nul "%KC_LOCAL%/realms/kilnmark" && goto open
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
