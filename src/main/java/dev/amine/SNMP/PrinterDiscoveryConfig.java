package dev.amine.SNMP;

public class PrinterDiscoveryConfig {
    // Existing OIDs
    public static final String SYSTEM_DESCRIPTION = "1.3.6.1.2.1.1.1.0";
    public static final String PRINTER_MODEL_NAME = "1.3.6.1.2.1.43.5.1.1.16.1";
    public static final String PRINTER_SERIAL_NUMBER = "1.3.6.1.2.1.43.11.1.1.6.1.1";
    public static final String PRINTER_STATUS = "1.3.6.1.2.1.25.3.2.1.5.1";
    public static final String PRINTER_PAGE_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1";

    // New OIDs for additional information
    public static final String MAC_ADDRESS = "1.3.6.1.2.1.2.2.1.6.1"; // Interface physical address

    // Toner level OIDs (for color printers)
    public static final String BLACK_TONER_LEVEL = "1.3.6.1.2.1.43.11.1.1.9.1.1"; // Black
    public static final String CYAN_TONER_LEVEL = "1.3.6.1.2.1.43.11.1.1.9.1.2"; // Cyan
    public static final String MAGENTA_TONER_LEVEL = "1.3.6.1.2.1.43.11.1.1.9.1.3"; // Magenta
    public static final String YELLOW_TONER_LEVEL = "1.3.6.1.2.1.43.11.1.1.9.1.4"; // Yellow

    public static final String[] COMMUNITY = {"public", "private", "community", "network"};

    private PrinterDiscoveryConfig() {
        throw new AssertionError("Cannot instantiate PrinterDiscoveryConfig");
    }
}