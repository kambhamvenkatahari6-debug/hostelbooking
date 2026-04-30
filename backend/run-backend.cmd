@echo off
setlocal
set "ROOT=%~dp0"
set "SRC=%ROOT%src"
set "OUT=%ROOT%out"
cd /d "%ROOT%"
if not exist "%OUT%" mkdir "%OUT%"

javac -d "%OUT%" "%SRC%\Main.java"
if errorlevel 1 (
  echo Compile failed.
  pause
  exit /b 1
)

echo Backend running at http://localhost:8080
java -cp "%OUT%" Main
