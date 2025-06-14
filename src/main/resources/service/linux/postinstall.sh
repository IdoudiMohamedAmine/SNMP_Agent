#!/bin/bash
# Simple post-install script for Linux
echo "Printwatch has been installed successfully!"
echo "You can find the application in your applications menu."

# Make sure the executable has proper permissions
chmod +x /opt/printwatch/bin/Printwatch

# Create a simple desktop entry (optional)
if [ -d "/usr/share/applications" ]; then
    cat > /usr/share/applications/printwatch.desktop << EOF
[Desktop Entry]
Name=Printwatch
Comment=SNMP Print Monitoring Agent
Exec=/opt/printwatch/bin/Printwatch
Icon=/opt/printwatch/lib/app/Printwatch.png
Terminal=false
Type=Application
Categories=System;Network;
EOF
fi

exit 0