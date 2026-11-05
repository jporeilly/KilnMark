@echo off
rem Stops Kilnmark, and Keycloak too when .env says COMPOSE_PROFILES=auth. Its data stays: the
rem volumes are kept, and start-kilnmark.cmd starts it again where it left off.
setlocal
cd /d "%~dp0"
set "COMPOSE="
where podman >nul 2>&1 && set "COMPOSE=podman compose"
if not defined COMPOSE where docker >nul 2>&1 && set "COMPOSE=docker compose"
if not defined COMPOSE (
  echo Neither Podman nor Docker was found.
  pause
  exit /b 1
)
echo Stopping Kilnmark...
%COMPOSE% down
if errorlevel 1 (
  echo Kilnmark could not be stopped cleanly. See: %COMPOSE% ps
  pause
  exit /b 1
)
echo Kilnmark is stopped. Its data is kept.
ping -n 3 127.0.0.1 >nul
exit /b 0
