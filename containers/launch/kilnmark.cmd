@echo off
rem Kilnmark control script for Windows: runs kilnmark.ps1 with the same arguments, so it works
rem from cmd, PowerShell or a double-click whatever the execution policy. See: kilnmark.cmd help
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0kilnmark.ps1" %*
exit /b %errorlevel%
