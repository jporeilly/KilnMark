@echo off
rem Starts Kilnmark and opens it in the browser: kilnmark.cmd up, for the shortcut
rem create-shortcut.ps1 makes. On a failure the window stays open so you can read why.
call "%~dp0kilnmark.cmd" up %*
if errorlevel 1 (
  echo.
  pause
  exit /b 1
)
exit /b 0
