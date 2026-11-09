@echo off
rem Kilnmark control script for Windows: runs kilnmark.ps1 with the same arguments, so it works
rem from cmd, PowerShell or a double-click whatever the execution policy. See: kilnmark.cmd help
rem Command Prompt reads a batch file line by line as it runs it, and uninstall deletes this file:
rem so, on the same line, when the file is gone, it ends the batch at once ((goto)) and sets 0
rem (uninstall removes the program folder last, once all else is done), with no "cannot find" error.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0kilnmark.ps1" %* & if not exist "%~f0" (goto) 2>nul & cmd /d /c exit 0
exit /b %errorlevel%
