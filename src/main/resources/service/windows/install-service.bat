@echo off
set SERVICE_NAME=PrintwatchAgent
set DISPLAY_NAME=Printwatch SNMP Agent
set BIN_PATH="%~dp0bin\Printwatch.exe"

sc create %SERVICE_NAME% binPath=%BIN_PATH% start=auto DisplayName="%DISPLAY_NAME%"
sc description %SERVICE_NAME% "Monitors printer status via SNMP"
sc failure %SERVICE_NAME% reset=0 actions=restart/5000
sc start %SERVICE_NAME%