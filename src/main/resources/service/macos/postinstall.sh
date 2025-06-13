#!/bin/bash

# Create user and group
dscl . -create /Groups/_printwatch
dscl . -create /Groups/_printwatch PrimaryGroupID 998
dscl . -create /Users/_printwatch
dscl . -create /Users/_printwatch UserShell /usr/bin/false
dscl . -create /Users/_printwatch RealName "Printwatch Service"
dscl . -create /Users/_printwatch UniqueID 998
dscl . -create /Users/_printwatch PrimaryGroupID 998
dscl . -create /Users/_printwatch NFSHomeDirectory /var/empty

# Create log directory
mkdir -p /var/log/printwatch
chown _printwatch:_printwatch /var/log/printwatch

# Install LaunchDaemon
cp "/Applications/Printwatch.app/Contents/com.abcsolpro.printwatch.plist" /Library/LaunchDaemons/
chmod 644 /Library/LaunchDaemons/com.abcsolpro.printwatch.plist
chown root:wheel /Library/LaunchDaemons/com.abcsolpro.printwatch.plist

# Load and start the service
launchctl load /Library/LaunchDaemons/com.abcsolpro.printwatch.plist
launchctl start com.abcsolpro.printwatch

echo "Printwatch service installed and started successfully"