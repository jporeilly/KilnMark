@echo off
rem Stops Kilnmark, and Keycloak too when .env says COMPOSE_PROFILES=auth. Its data stays: the
rem volumes are kept, and start-kilnmark.cmd starts it again where it left off.
setlocal
cd /d "%~dp0"
set "COMPOSE="
podman compose version >nul 2>&1 && set "COMPOSE=podman compose"
if not defined COMPOSE docker compose version >nul 2>&1 && set "COMPOSE=docker compose"
if not defined COMPOSE (
  echo Neither podman compose nor docker compose could be used.
  pause
  exit /b 1
)
rem Keycloak's services stop too when the profile is on (some compose tools need it named).
set "KM_PROFILES=none"
if exist ".env" for /f "usebackq eol=# tokens=1,2 delims== " %%a in (".env") do (
  if "%%a"=="COMPOSE_PROFILES" set "KM_PROFILES=%%~b"
)
set "KM_PROFILE_ARG="
echo ,%KM_PROFILES: =%, | findstr /c:",auth," >nul && set "KM_PROFILE_ARG=--profile auth"
echo Stopping Kilnmark...
%COMPOSE% %KM_PROFILE_ARG% down
if errorlevel 1 (
  echo Kilnmark could not be stopped cleanly. See: %COMPOSE% ps
  pause
  exit /b 1
)
echo Kilnmark is stopped. Its data is kept.
ping -n 3 127.0.0.1 >nul
exit /b 0
