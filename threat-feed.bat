@echo off
rem Threat feed jednym kliknieciem (docs/redteam-feed.md):
rem   1. pobiera nowe podatnosci z OSV.dev (bez internetu: zapisany snapshot),
rem   2. sprawdza regresje aktywnego feedu i aktywnego + propozycje,
rem   3. otwiera raport: co peklo i jakie gotowe reguly mozna wkleic.
rem Niczego nie zmienia w backend\config\signatures\active.yaml - o tym decydujesz Ty.
setlocal
chcp 65001 >nul
cd /d "%~dp0backend"

rem JAVA_HOME wskazuje nieistniejacy katalog? Wez JDK pobrany przez IntelliJ (%USERPROFILE%\.jdks).
if not exist "%JAVA_HOME%\bin\java.exe" (
    for /d %%J in ("%USERPROFILE%\.jdks\*") do if exist "%%J\bin\java.exe" set "JAVA_HOME=%%J"
)

echo === Threat feed: OSV -^> regresja -^> raport ===
echo.
call "%~dp0backend\gradlew.bat" threatFeed -q
if errorlevel 1 (
    echo.
    echo OSV niedostepne - uzywam zapisanego snapshotu offline...
    call "%~dp0backend\gradlew.bat" threatFeed -q --args=--offline
    if errorlevel 1 (
        echo.
        echo BLAD - zobacz komunikat wyzej.
        pause
        exit /b 1
    )
)

echo.
echo Otwieram raport w Notatniku...
if not defined THREAT_FEED_NO_OPEN start "" notepad "%~dp0tests\redteam\proposals\latest\report.md"
pause
