package dev.amine.SNMP;

public class PrinterDiscoveryConfig {
    public static final String SYSTEM_DESCRIPTION = "1.3.6.1.2.1.1.1.0";
    public static final String PRINTER_MODEL_NAME = "1.3.6.1.2.1.43.5.1.1.16.1";
    public static final String PRINTER_SERIAL_NUMBER = "1.3.6.1.2.1.43.11.1.1.6.1.1";
    public static final String PRINTER_STATUS = "1.3.6.1.2.1.25.3.2.1.5.1";
    public static final String PRINTER_PAGE_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1";
    public static final String[] COMMUNITY = {"public", "private", "community", "network"};
    private PrinterDiscoveryConfig() {
        throw new AssertionError("Cannot instantiate PrinterDiscoveryConfig");
    }
}
