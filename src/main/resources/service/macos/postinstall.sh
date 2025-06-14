#!/bin/bash
# Simple post-install script for macOS
echo "Printwatch has been installed successfully!"
echo "You can find the application in your Applications folder."

# Set proper permissions
chmod +x "/Applications/Printwatch.app/Contents/MacOS/Printwatch"

exit 0