package dev.amine.SNMP;

public class PrinterDiscoveryConfig {
    // Basic system info
    public static final String SYSTEM_NAME = "1.3.6.1.2.1.1.5.0";
    public static final String SYSTEM_DESCRIPTION = "1.3.6.1.2.1.1.1.0";

    // Printer-specific OIDs
    public static final String PRINTER_MODEL = "1.3.6.1.2.1.43.5.1.1.16.1";
    public static final String SERIAL_NUMBER = "1.3.6.1.2.1.43.5.1.1.17.1";
    public static final String MAC_ADDRESS = "1.3.6.1.2.1.2.2.1.6.1";
    public static final String PRINTER_STATUS = "1.3.6.1.2.1.25.3.2.1.5.1";

    // Page counts
    public static final String TOTAL_PAGE_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1";
    public static final String COLOR_PAGE_COUNT = "1.3.6.1.4.1.253.8.53.13.2.1.4.1.1.2";
    public static final String MONO_PAGE_COUNT = "1.3.6.1.4.1.253.8.53.13.2.1.4.1.1.1";

    // Toner information
    public static final String TONER_LEVELS = "1.3.6.1.2.1.43.11.1.1.9";
    public static final String TONER_MAX_LEVELS = "1.3.6.1.2.1.43.11.1.1.8";
    public static final String TONER_DESCRIPTION = "1.3.6.1.2.1.43.11.1.1.6";

    // Paper tray information
    public static final String PAPER_TRAY_DESCRIPTION = "1.3.6.1.2.1.43.8.2.1.18";
    public static final String PAPER_TRAY_LEVELS = "1.3.6.1.2.1.43.8.2.1.10";
    public static final String PAPER_TRAY_MAX_LEVELS = "1.3.6.1.2.1.43.8.2.1.9";

    public static final String[] COMMUNITY = {"public", "private"};
}