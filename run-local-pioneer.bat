@echo off
setlocal enabledelayedexpansion

set "PLATFORM=win"
set "CLASSPATH=included"

echo =======================================================
echo  FAF ICE Adapter vs Pioneer - Hybrid Local Run
echo  (2x faf-ice-adapter [Java] + 2x faf-pioneer [Go])
echo =======================================================

REM --- 1. Build Java Artifacts ---
echo.
echo [1/4] Building Java shadow JARs...
call gradlew.bat -PjavafxPlatform=%PLATFORM% -PwebrtcPlatform=%PLATFORM% -PjavafxClasspath=%CLASSPATH% :client:shadowJar :server:shadowJar :ice-adapter:shadowJar
if errorlevel 1 (
    echo ERROR: Gradle build failed!
    exit /b 1
)

REM --- 2. Copy JARs to root ---
echo.
echo [2/4] Copying JARs to root...

set "ICE_JAR="
for %%f in (ice-adapter\build\libs\faf-ice-adapter-*.jar) do (
    copy /Y "%%f" "faf-ice-adapter.jar" >nul
    set "ICE_JAR=%%f"
)
if "%ICE_JAR%"=="" (
    echo ERROR: faf-ice-adapter JAR not found!
    exit /b 1
)

set "CLIENT_JAR="
for %%f in (client\build\libs\client-*-all.jar) do (
    set "CLIENT_JAR=%%f"
)
if "%CLIENT_JAR%"=="" (
    echo ERROR: client shadow JAR not found!
    exit /b 1
)
copy /Y "!CLIENT_JAR!" "test-client.jar" >nul

set "SERVER_JAR="
for %%f in (server\build\libs\server-*-all.jar) do (
    set "SERVER_JAR=%%f"
)
if "%SERVER_JAR%"=="" (
    echo ERROR: server shadow JAR not found!
    exit /b 1
)
copy /Y "!SERVER_JAR!" "test-server.jar" >nul

REM --- 3. Build Go Pioneer Adapter if needed ---
echo.
echo [3/4] Checking Go faf-adapter binary...
if not exist "temp\faf-pioneer\faf-adapter.exe" (
    echo Compiling faf-adapter.exe from temp\faf-pioneer\cmd\faf-adapter...
    pushd "temp\faf-pioneer"
    go build -o faf-adapter.exe ./cmd/faf-adapter
    popd
)

if exist "temp\faf-pioneer\faf-adapter.exe" (
    echo Go faf-adapter binary ready.
) else (
    echo WARNING: faf-adapter.exe could not be compiled, Pioneer clients will try auto-compiling on launch.
)

REM --- 4. Start Server & Clients ---
echo.
echo [4/4] Starting test server and 4 clients...

echo Starting TestServer...
start "TestServer" cmd /k "java -jar test-server.jar"
ping 127.0.0.1 -n 4 >nul

echo Starting Client 1: Java ICE Adapter (Player 1 - Java-Host)...
start "Client 1 [Java - Host]" cmd /k "java -jar test-client.jar --name=JavaPlayer1 --game-id=1000"

ping 127.0.0.1 -n 2 >nul
echo Starting Client 2: Java ICE Adapter (Player 2 - Java-Peer)...
start "Client 2 [Java - Peer]" cmd /k "java -jar test-client.jar --name=JavaPlayer2 --game-id=1000"

ping 127.0.0.1 -n 2 >nul
echo Starting Client 3: FAF Pioneer (Player 3 - Pioneer-A)...
start "Client 3 [Pioneer - A]" cmd /k "java -cp test-client.jar client.pioneer.PioneerTestClient --user-id=3 --user-name=PioneerUser3 --game-id=1000"

ping 127.0.0.1 -n 2 >nul
echo Starting Client 4: FAF Pioneer (Player 4 - Pioneer-B)...
start "Client 4 [Pioneer - B]" cmd /k "java -cp test-client.jar client.pioneer.PioneerTestClient --user-id=4 --user-name=PioneerUser4 --game-id=1000"

echo.
echo =======================================================
echo  All 4 test clients started successfully!
echo    - Client 1: Java ICE Adapter (JavaPlayer1)
echo    - Client 2: Java ICE Adapter (JavaPlayer2)
echo    - Client 3: FAF Pioneer Go (PioneerUser3)
echo    - Client 4: FAF Pioneer Go (PioneerUser4)
echo =======================================================
echo.
echo To stop all processes, simply close their console windows.
echo.
pause
