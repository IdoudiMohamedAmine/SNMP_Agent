@echo off
echo Installing SNMP Agent as a Windows service...
sc create PrintwatchService binPath= "%~dp0\SNMP Agent\SNMP Agent.exe" start= auto DisplayName= "Printwatch Service"
sc description PrintwatchService "SNMP Printer Monitoring Agent"
sc start PrintwatchService
echo Service installed successfully.
pause