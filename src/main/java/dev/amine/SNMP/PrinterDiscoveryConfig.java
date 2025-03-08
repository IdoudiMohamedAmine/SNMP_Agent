package dev.amine.SNMP;

public class PrinterDiscoveryConfig {
    // RFC 1759 standard MIB OIDs for printer discovery

    // System Group (RFC 1213 MIB-II)
    public static final String SYSTEM_DESCRIPTION = "1.3.6.1.2.1.1.1.0";
    public static final String SYSTEM_OBJECT_ID = "1.3.6.1.2.1.1.2.0";
    public static final String SYSTEM_NAME = "1.3.6.1.2.1.1.5.0";
    public static final String SYSTEM_LOCATION = "1.3.6.1.2.1.1.6.0";

    // Interface Group for MAC address (RFC 1213 MIB-II)
    public static final String IF_PHYSICAL_ADDRESS = "1.3.6.1.2.1.2.2.1.6.1"; // MAC address

    // Printer MIB (RFC 1759) OIDs
    public static final String PRINTER_HR_DEVICE_STATUS = "1.3.6.1.2.1.25.3.2.1.5.1"; // Host Resources MIB status

    // General Printer (RFC 1759)
    public static final String PRINTER_INFO = "1.3.6.1.2.1.43.5.1.1"; // Printer information
    public static final String PRINTER_MODEL = "1.3.6.1.2.1.43.5.1.1.16.1"; // Model name
    public static final String PRINTER_CONSOLE_DISPLAY = "1.3.6.1.2.1.43.16.5.1.2.1.1"; // Console message

    // Printer Supply OIDs (RFC 1759)
    public static final String PRINTER_SUPPLY_TABLE = "1.3.6.1.2.1.43.11.1.1";
    public static final String PRINTER_SUPPLY_DESCRIPTION = "1.3.6.1.2.1.43.11.1.1.6"; // Description of supply unit
    public static final String PRINTER_SUPPLY_UNIT = "1.3.6.1.2.1.43.11.1.1.7"; // Type of supply unit
    public static final String PRINTER_SUPPLY_MAX_LEVEL = "1.3.6.1.2.1.43.11.1.1.8"; // Max capacity
    public static final String PRINTER_SUPPLY_CURRENT_LEVEL = "1.3.6.1.2.1.43.11.1.1.9"; // Current level

    // Printer Counter OIDs (RFC 1759)
    public static final String PRINTER_COUNTERS = "1.3.6.1.2.1.43.10.2";
    public static final String PRINTER_COUNTER_UNITS = "1.3.6.1.2.1.43.10.2.1.3"; // Units for counter
    public static final String PRINTER_LIFETIME_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1"; // Total page count

    // Supply color index values (common in many implementations)
    public static final int BLACK_TONER_INDEX = 1;
    public static final int CYAN_TONER_INDEX = 2;
    public static final int MAGENTA_TONER_INDEX = 3;
    public static final int YELLOW_TONER_INDEX = 4;

    // Standard community strings to try
    public static final String[] COMMUNITY = {"public", "private", "community", "network"};

    private PrinterDiscoveryConfig() {
        throw new AssertionError("Cannot instantiate PrinterDiscoveryConfig");
    }
}