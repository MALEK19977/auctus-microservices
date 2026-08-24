@echo off
REM Redemarre UN seul service sans toucher aux autres.
REM
REM   restart-service.bat signature-service
REM   restart-service.bat qr-service
REM
REM Utile apres une modification de code Java. Les scripts Python sont relus
REM a chaque requete : aucun redemarrage n'est necessaire pour les modifier.

setlocal
set PROJECT_PATH=%~dp0

if "%~1"=="" (
    echo Usage: restart-service.bat ^<nom-du-service^>
    echo.
    echo   service-registry    port 8761
    echo   api-gateway         port 8080
    echo   auth-service        port 8081
    echo   cheque-service      port 8082
    echo   ocr-service         port 8083
    echo   qr-service          port 8084
    echo   signature-service   port 8085
    exit /b 1
)

set SERVICE=%~1

if not exist "%PROJECT_PATH%backend\%SERVICE%" (
    echo ERREUR: service introuvable: %SERVICE%
    exit /b 1
)

REM Chaque service tourne dans sa propre fenetre, titree "SRV - <nom>" par
REM start-all.bat. On la ferme si elle existe, sinon on laisse l'utilisateur
REM fermer manuellement l'ancienne fenetre.
taskkill /FI "WINDOWTITLE eq SRV - %SERVICE%*" /F >nul 2>&1

echo Redemarrage de %SERVICE%...
start "SRV - %SERVICE%" cmd /k "cd /d "%PROJECT_PATH%backend\%SERVICE%" && mvn spring-boot:run"
echo Lance. Attendez ~15s le message "Started ...Application".
