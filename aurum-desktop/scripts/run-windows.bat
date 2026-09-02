@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-windows.ps1"
if errorlevel 1 pause
endlocal