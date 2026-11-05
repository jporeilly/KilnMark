@echo off
rem Stops Kilnmark; its data is kept: kilnmark.cmd down, for the shortcut create-shortcut.ps1 makes.
call "%~dp0kilnmark.cmd" down
if errorlevel 1 (
  echo.
  pause
  exit /b 1
)
ping -n 3 127.0.0.1 >nul
exit /b 0
