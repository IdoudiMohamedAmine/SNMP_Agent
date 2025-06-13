#!/bin/bash

# Create user and group
if ! getent group printwatch > /dev/null 2>&1; then
    groupadd --system printwatch
fi

if ! getent passwd printwatch > /dev/null 2>&1; then
    useradd --system --gid printwatch --home-dir /opt/printwatch \
            --shell /bin/false --comment "Printwatch SNMP Agent" printwatch
fi

# Create directories
mkdir -p /var/log/printwatch
mkdir -p /var/lib/printwatch
chown printwatch:printwatch /var/log/printwatch
chown printwatch:printwatch /var/lib/printwatch
chmod 755 /var/log/printwatch
chmod 755 /var/lib/printwatch

# Set ownership
chown -R printwatch:printwatch /opt/printwatch

# Install and enable systemd service
cp /opt/printwatch/lib/printwatch.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable printwatch.service
systemctl start printwatch.service

echo "Printwatch service installed and started successfully"