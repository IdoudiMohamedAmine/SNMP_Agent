package dev.amine.SNMP;

public class PrinterDiscoveryConfig {
    public static final String SYSTEM_DESCRIPTION = "1.3.6.1.2.1.1.1.0";
    public static final String SYSTEM_NAME = "1.3.6.1.2.1.1.5.0";
    public static final String SYSTEM_LOCATION = "1.3.6.1.2.1.1.6.0";
    public static final String IF_PHYSICAL_ADDRESS = "1.3.6.1.2.1.2.2.1.6.1";
    public static final String PRINTER_HR_DEVICE_STATUS = "1.3.6.1.2.1.25.3.2.1.5.1";
    public static final String PRINTER_MODEL = "1.3.6.1.2.1.43.5.1.1.16.1";
    public static final String PRINTER_CONSOLE_DISPLAY = "1.3.6.1.2.1.43.16.5.1.2.1.1";
    public static final String PRINTER_SUPPLY_DESCRIPTION = "1.3.6.1.2.1.43.11.1.1.6";
    public static final String PRINTER_SUPPLY_UNIT = "1.3.6.1.2.1.43.11.1.1.7";
    public static final String PRINTER_SUPPLY_MAX_LEVEL = "1.3.6.1.2.1.43.11.1.1.8";
    public static final String PRINTER_SUPPLY_CURRENT_LEVEL = "1.3.6.1.2.1.43.11.1.1.9";
    public static final String PRINTER_LIFETIME_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1";

    public static final int BLACK_TONER_INDEX = 1;
    public static final int CYAN_TONER_INDEX = 2;
    public static final int MAGENTA_TONER_INDEX = 3;
    public static final int YELLOW_TONER_INDEX = 4;

    public static final String[] COMMUNITY = {"public", "private"};

    private PrinterDiscoveryConfig() {}
}