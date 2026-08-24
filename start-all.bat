@echo off
title AUCTUS - START ALL (Backend + Frontend)
color 0A
setlocal

REM Usage:
REM   start-all.bat          incremental build, reuses caches (fast, ~40s)
REM   start-all.bat clean    wipes build output first (slow, needs internet)

set CLEAN=0
if /i "%~1"=="clean" set CLEAN=1

echo ============================================================
echo   AUCTUS - START ALL
if %CLEAN%==1 (echo   Mode: CLEAN rebuild) else (echo   Mode: incremental)
echo ============================================================
echo.

REM =============================================
REM 1. Arret des services Java en cours
REM =============================================
echo [1/4] Arret des services Java en cours...
taskkill /F /IM java.exe >nul 2>&1
echo    OK
echo.

REM =============================================
REM 2. Nettoyage (uniquement en mode clean)
REM =============================================
if %CLEAN%==1 (
    echo [2/4] Nettoyage des builds...
    REM Le cache Maven ^(%%USERPROFILE%%\.m2\repository^) n'est JAMAIS supprime :
    REM le vider force un re-telechargement complet de toutes les dependances.
    if exist "%~dp0frontend\auctus-frontend\.angular" rmdir /s /q "%~dp0frontend\auctus-frontend\.angular" 2>nul
    if exist "%~dp0frontend\auctus-frontend\dist" rmdir /s /q "%~dp0frontend\auctus-frontend\dist" 2>nul
    for %%s in (service-registry api-gateway auth-service ocr-service qr-service signature-service client-service cheque-service) do (
        if exist "%~dp0backend\%%s\target" rmdir /s /q "%~dp0backend\%%s\target" 2>nul
    )
    echo    OK
) else (
    echo [2/4] Nettoyage ignore ^(mode incremental^)
)
echo.

REM =============================================
REM 3. Compilation (une seule invocation Maven)
REM =============================================
echo [3/4] Compilation des services backend...
cd /d "%~dp0backend"
call mvn -q -T 1C compile -DskipTests
if errorlevel 1 (
    echo.
    echo    ECHEC DE LA COMPILATION - corrigez les erreurs ci-dessus.
    pause
    exit /b 1
)
echo    OK
echo.

REM =============================================
REM 4. Lancement
REM =============================================
echo [4/4] Lancement du Backend...

REM Le registre doit demarrer en premier : les autres services s'y enregistrent.
start "SRV - Service Registry" cmd /k "cd /d "%~dp0backend\service-registry" && mvn spring-boot:run"
echo    Attente du Service Registry (port 8761)...
powershell -NoProfile -Command "$d=(Get-Date).AddSeconds(90); while((Get-Date) -lt $d){ try{ if((Invoke-WebRequest -Uri 'http://localhost:8761/actuator/health' -TimeoutSec 2 -UseBasicParsing).StatusCode -eq 200){exit 0} }catch{}; try{ $c=New-Object Net.Sockets.TcpClient; $c.Connect('localhost',8761); $c.Close(); exit 0 }catch{}; Start-Sleep -Seconds 2 }; exit 1"

for %%s in (api-gateway auth-service ocr-service qr-service signature-service client-service collab-service cheque-service) do (
    start "SRV - %%s" cmd /k "cd /d "%~dp0backend\%%s" && mvn spring-boot:run"
    timeout /t 2 /nobreak >nul
)
echo    Backend lance (7 services)
echo.

cd /d "%~dp0frontend\auctus-frontend"
start "FRONT - Angular" cmd /k "npx ng serve --open"
echo    Frontend lance
echo.

echo ============================================================
echo   AUCTUS EST EN COURS D'EXECUTION
echo ============================================================
echo.
echo   SERVICE REGISTRY:  http://localhost:8761
echo   API GATEWAY:       http://localhost:8080
echo   AUTH SERVICE:      http://localhost:8081
echo   CHEQUE SERVICE:    http://localhost:8082
echo   OCR SERVICE:       http://localhost:8083
echo   QR SERVICE:        http://localhost:8084
echo   SIGNATURE SERVICE: http://localhost:8085
echo   CLIENT SERVICE:    http://localhost:8086
echo   FRONTEND:          http://localhost:4200
echo.
echo   Pour redemarrer UN seul service : restart-service.bat ^<nom^>
echo   Pour tout arreter                : taskkill /F /IM java.exe
echo.
echo ============================================================
pause
