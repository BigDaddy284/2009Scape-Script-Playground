@echo off
setlocal
title Install 2009 OSRS Code Edition
echo This copies Code Edition into your 2009scape single-player folder.
echo.
set "TARGET=%~1"
if "%TARGET%"=="" set /p "TARGET=Drag your 2009scape folder here (or type its path) and press Enter: "
set "TARGET=%TARGET:"=%"
if not exist "%TARGET%\game\server.jar" (
  echo.
  echo That folder does not look like the 2009scape single-player release.
  echo It should contain game\server.jar and the jre folder.
  pause
  exit /b 1
)
xcopy "%~dp0overlay\*" "%TARGET%\" /E /I /Y /Q >NUL
if errorlevel 1 (
  echo Copying failed.
  pause
  exit /b 1
)
echo.
echo Done. Start "2009 OSRS Code Edition.exe" in %TARGET%
pause
