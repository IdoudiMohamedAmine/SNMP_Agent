#!/bin/bash
# Install systemd service
cp /opt/snmp-agent/app/snmp-agent.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable snmp-agent.service
systemctl start snmp-agent.service