@echo off
title AUCTUS Microservices Stopper
color 0C

echo ========================================
echo   ARRET DE TOUS LES SERVICES
echo ========================================
echo.

echo Arret des processus Java...
taskkill /F /IM java.exe 2>nul
taskkill /F /IM javaw.exe 2>nul

echo.
echo ========================================
echo   TOUS LES SERVICES ONT ETE ARRETES
echo ========================================
pause