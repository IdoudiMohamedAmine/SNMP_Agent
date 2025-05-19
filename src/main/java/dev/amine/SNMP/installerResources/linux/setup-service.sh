#!/bin/bash

INSTALL_PATH=$1
SERVICE_NAME="printwatch"
JAR_FILE="${project.artifactId}-${project.version}-jar-with-dependencies.jar"

echo "Installing Printwatch as a system service..."

# Check if JAR exists
if [ ! -f "${INSTALL_PATH}/${JAR_FILE}" ]; then
    echo "Error: JAR file not found at ${INSTALL_PATH}/${JAR_FILE}"
    exit 1
fi

# Create service file for systemd
cat > /tmp/${SERVICE_NAME}.service << EOL
[Unit]
Description=Printwatch SNMP Monitoring Service
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/java -jar ${INSTALL_PATH}/${JAR_FILE}
WorkingDirectory=${INSTALL_PATH}
User=root
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOL

# Create init.d script for older systems
cat > /tmp/${SERVICE_NAME} << EOL
#!/bin/bash
### BEGIN INIT INFO
# Provides:          ${SERVICE_NAME}
# Required-Start:    $network $remote_fs $syslog
# Required-Stop:     $network $remote_fs $syslog
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: Printwatch SNMP service
# Description:       Monitors network printers using SNMP
### END INIT INFO

DAEMON="/usr/bin/java"
DAEMON_ARGS="-jar ${INSTALL_PATH}/${JAR_FILE}"
DAEMON_USER="root"
PIDFILE=/var/run/${SERVICE_NAME}.pid
DESC="Printwatch SNMP service"

do_start() {
    echo "Starting \$DESC"
    start-stop-daemon --start --quiet --background --make-pidfile --pidfile \$PIDFILE --chuid \$DAEMON_USER --exec \$DAEMON -- \$DAEMON_ARGS
    return \$?
}

do_stop() {
    echo "Stopping \$DESC"
    start-stop-daemon --stop --quiet --pidfile \$PIDFILE
    rm -f \$PIDFILE
    return \$?
}

case "\$1" in
    start)
        do_start
        ;;
    stop)
        do_stop
        ;;
    restart)
        do_stop
        do_start
        ;;
    status)
        status_of_proc -p \$PIDFILE "\$DAEMON" "\$DESC"
        ;;
    *)
        echo "Usage: \$0 {start|stop|restart|status}"
        exit 1
        ;;
esac
exit 0
EOL

# Install service based on available init system
if command -v systemctl &> /dev/null; then
    # For systemd systems
    echo "Installing service with systemd..."
    sudo mv /tmp/${SERVICE_NAME}.service /etc/systemd/system/
    sudo chmod 644 /etc/systemd/system/${SERVICE_NAME}.service
    sudo systemctl daemon-reload
    sudo systemctl enable ${SERVICE_NAME}.service
    sudo systemctl start ${SERVICE_NAME}.service
    echo "Service installed successfully with systemd"
elif command -v service &> /dev/null; then
    # For init.d systems
    echo "Installing service with init.d..."
    sudo mv /tmp/${SERVICE_NAME} /etc/init.d/
    sudo chmod +x /etc/init.d/${SERVICE_NAME}

    if command -v update-rc.d &> /dev/null; then
        # Debian-based
        sudo update-rc.d ${SERVICE_NAME} defaults
    elif command -v chkconfig &> /dev/null; then
        # RedHat-based
        sudo chkconfig --add ${SERVICE_NAME}
        sudo chkconfig ${SERVICE_NAME} on
    fi

    sudo service ${SERVICE_NAME} start
    echo "Service installed successfully with init.d"
else
    # Fallback for other systems - create a desktop autostart entry
    echo "No systemd or init.d found. Creating user autostart entry..."
    mkdir -p ~/.config/autostart
    cat > ~/.config/autostart/${SERVICE_NAME}.desktop << EOL
[Desktop Entry]
Type=Application
Name=Printwatch
Exec=/usr/bin/java -jar ${INSTALL_PATH}/${JAR_FILE}
Terminal=false
Hidden=false
X-GNOME-Autostart-enabled=true
Comment=SNMP Printer Monitoring Agent
EOL
    chmod +x ~/.config/autostart/${SERVICE_NAME}.desktop
    echo "Created user autostart entry"
fi

# Create desktop shortcut
mkdir -p ~/Desktop
cat > ~/Desktop/${SERVICE_NAME}.desktop << EOL
[Desktop Entry]
Type=Application
Name=Printwatch
Icon=${INSTALL_PATH}/icon.png
Exec=/usr/bin/java -jar ${INSTALL_PATH}/${JAR_FILE}
Terminal=false
Categories=Utility;
Comment=SNMP Printer Monitoring Agent
EOL
chmod +x ~/Desktop/${SERVICE_NAME}.desktop

echo "Installation complete!"