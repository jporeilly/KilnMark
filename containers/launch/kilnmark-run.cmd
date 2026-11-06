@echo off
rem kilnmark-run for Windows schedulers (Task Scheduler, Control-M and AutoSys agents): runs
rem kilnmark-run.ps1 with the same arguments, whatever the execution policy, and exits with its
rem exit code. See docs/guide/external-schedulers.md, or: kilnmark-run.cmd --help
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0kilnmark-run.ps1" %*
exit /b %errorlevel%
