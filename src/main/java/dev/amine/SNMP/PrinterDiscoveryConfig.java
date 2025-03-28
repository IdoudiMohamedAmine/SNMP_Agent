package dev.amine.SNMP;

import java.util.HashMap;
import java.util.Map;

public class PrinterDiscoveryConfig {
    public static final String SYSTEM_NAME = "1.3.6.1.2.1.1.5.0";
    public static final String SYSTEM_DESCRIPTION = "1.3.6.1.2.1.1.1.0";
    public static final String MAC_ADDRESS = "1.3.6.1.2.1.2.2.1.6.1";
    public static final String PRINTER_STATUS = "1.3.6.1.2.1.25.3.2.1.5.1";
    public static final String SERIAL_NUMBER = "1.3.6.1.2.1.43.5.1.1.17.1";
    public static final String TOTAL_PAGE_COUNT = "1.3.6.1.2.1.43.10.2.1.4.1.1";
    public static final String TONER_LEVELS = "1.3.6.1.2.1.43.11.1.1.9";
    public static final String TONER_MAX_LEVELS = "1.3.6.1.2.1.43.11.1.1.8";
    public static final String TONER_DESCRIPTION = "1.3.6.1.2.1.43.11.1.1.6";
    public static final String PAPER_TRAY_DESCRIPTION = "1.3.6.1.2.1.43.8.2.1.18";
    public static final String PAPER_TRAY_LEVELS = "1.3.6.1.2.1.43.8.2.1.10";
    public static final String PAPER_TRAY_MAX_LEVELS = "1.3.6.1.2.1.43.8.2.1.9";
    public static final String[] COMMUNITY = {"public", "private"};

    private static final Map<String, Map<String, String>> VENDOR_SPECIFIC_OIDS = new HashMap<>();

    static {
        Map<String, String> hpOids = new HashMap<>();
        hpOids.put("PRINTER_MODEL", "1.3.6.1.2.1.43.5.1.1.16.1");
        hpOids.put("COLOR_PAGE_COUNT", "1.3.6.1.4.1.11.2.3.9.4.2.1.4.1.2.6");
        VENDOR_SPECIFIC_OIDS.put("HP", hpOids);

        Map<String, String> xeroxOids = new HashMap<>();
        xeroxOids.put("COLOR_PAGE_COUNT", "1.3.6.1.4.1.253.8.53.13.2.1.4.1.1.2");
        VENDOR_SPECIFIC_OIDS.put("Xerox", xeroxOids);

        Map<String, String> genericOids = new HashMap<>();
        genericOids.put("PRINTER_MODEL", "1.3.6.1.2.1.43.5.1.1.16.1");
        VENDOR_SPECIFIC_OIDS.put("Generic", genericOids);
    }

    public static String getVendorSpecificOid(String vendor, String oidKey) {
        Map<String, String> vendorOids = VENDOR_SPECIFIC_OIDS.getOrDefault(vendor,
                VENDOR_SPECIFIC_OIDS.get("Generic"));
        return vendorOids.get(oidKey);
    }

    public static String[] getAllKnownVendors() {
        return VENDOR_SPECIFIC_OIDS.keySet().toArray(new String[0]);
    }
}