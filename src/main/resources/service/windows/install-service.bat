@echo off
echo Installing Printwatch Service...

REM The Windows service is automatically handled by jpackage --win-service
REM This script is just for manual installation if needed

sc create "PrintwatchAgent" binPath= "%~dp0\..\Printwatch.exe" DisplayName= "Printwatch SNMP Agent" start= auto
sc description "PrintwatchAgent" "SNMP Print Monitoring Agent Service"
sc start "PrintwatchAgent"

echo Service installed and started successfully
pause