package dev.amine.SNMP;

import java.util.HashMap;
import java.util.Map;

public class PrinterDiscoveryConfig {

    // ===== Standard RFC3805 Printer MIB OIDs =====
    public static final String PRINTER_MODEL = "1.3.6.1.2.1.43.5.1.1.16.1";
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
    public static final String MEDIA_SIZE_SUPPORTED = "1.3.6.1.2.1.43.13.4.1.8"; // prtInputMediaName

    // ===== SNMP Community Strings =====
    public static final String[] COMMUNITY = {"public", "private"};

    // ===== Vendor-Specific OIDs =====
    private static final Map<String, Map<String, String>> VENDOR_SPECIFIC_OIDS = new HashMap<>();

    static {
        // HP/Hewlett-Packard OIDs
        Map<String, String> hpOids = new HashMap<>();
        hpOids.put("COLOR_PAGE_COUNT", "1.3.6.1.4.1.11.2.3.9.4.2.1.4.1.2.6");
        hpOids.put("MONO_PAGE_COUNT", "1.3.6.1.4.1.11.2.3.9.4.2.1.4.1.2.5");
        hpOids.put("DUPLEX_PAGE_COUNT", "1.3.6.1.4.1.11.2.3.9.4.2.1.4.1.2.7");
        hpOids.put("PRINTER_MODEL", "1.3.6.1.4.1.11.2.3.9.4.2.1.1.3.3.0");
        VENDOR_SPECIFIC_OIDS.put("HP", hpOids);

        // Xerox OIDs
        Map<String, String> xeroxOids = new HashMap<>();
        xeroxOids.put("COLOR_PAGE_COUNT", "1.3.6.1.4.1.253.8.53.13.2.1.4.1.1.2");
        xeroxOids.put("MONO_PAGE_COUNT", "1.3.6.1.4.1.253.8.53.13.2.1.4.1.1.1");
        xeroxOids.put("PRINTER_MODEL", "1.3.6.1.4.1.253.8.53.3.2.1.3.1.5.0");
        VENDOR_SPECIFIC_OIDS.put("Xerox", xeroxOids);

        // Canon OIDs
        Map<String, String> canonOids = new HashMap<>();
        canonOids.put("COLOR_PAGE_COUNT", "1.3.6.1.4.1.1602.1.11.1.3.1.4.1.1.1.1.20.1");
        canonOids.put("MONO_PAGE_COUNT", "1.3.6.1.4.1.1602.1.11.1.3.1.4.1.1.1.1.19.1");
        canonOids.put("PRINTER_MODEL", "1.3.6.1.4.1.1602.1.1.1.1.0");
        VENDOR_SPECIFIC_OIDS.put("Canon", canonOids);

        // Konica Minolta OIDs
        Map<String, String> konicaOids = new HashMap<>();
        konicaOids.put("COLOR_PAGE_COUNT", "1.3.6.1.4.1.18334.1.1.1.5.7.2.2.1.5.1.1");
        konicaOids.put("MONO_PAGE_COUNT", "1.3.6.1.4.1.18334.1.1.1.5.7.2.2.1.5.1.2");
        konicaOids.put("PRINTER_MODEL", "1.3.6.1.4.1.18334.1.1.1.1.1.2.0");
        VENDOR_SPECIFIC_OIDS.put("Konica Minolta", konicaOids);

        // Brother OIDs
        Map<String, String> brotherOids = new HashMap<>();
        brotherOids.put("COLOR_PAGE_COUNT", "1.3.6.1.4.1.2435.2.3.9.4.2.1.5.5.10.0");
        brotherOids.put("MONO_PAGE_COUNT", "1.3.6.1.4.1.2435.2.3.9.4.2.1.5.5.11.0");
        brotherOids.put("PRINTER_MODEL", "1.3.6.1.4.1.2435.2.3.9.1.1.7.0");
        VENDOR_SPECIFIC_OIDS.put("Brother", brotherOids);

        // Epson OIDs
        Map<String, String> epsonOids = new HashMap<>();
        epsonOids.put("COLOR_PAGE_COUNT", "1.3.6.1.4.1.1248.1.2.2.1.1.1.4.1.1");
        epsonOids.put("MONO_PAGE_COUNT", "1.3.6.1.4.1.1248.1.2.2.1.1.1.1.1.1");
        epsonOids.put("PRINTER_MODEL", "1.3.6.1.4.1.1248.1.1.3.1.3.8.0");
        VENDOR_SPECIFIC_OIDS.put("Epson", epsonOids);

        // Generic RFC3805 fallback OIDs
        Map<String, String> genericOids = new HashMap<>();
        genericOids.put("MONO_PAGE_COUNT", TOTAL_PAGE_COUNT);
        genericOids.put("COLOR_PAGE_COUNT", "1.3.6.1.2.1.43.10.2.1.5.1.1");
        genericOids.put("PRINTER_MODEL", PRINTER_MODEL);
        VENDOR_SPECIFIC_OIDS.put("Generic", genericOids);
    }

    /**
     * Get vendor-specific OID for a given vendor and OID key
     * Falls back to Generic OIDs if vendor not found
     *
     * @param vendor The printer vendor (HP, Xerox, Canon, etc.)
     * @param oidKey The OID key (COLOR_PAGE_COUNT, MONO_PAGE_COUNT, etc.)
     * @return The OID string or null if not found
     */
    public static String getVendorSpecificOid(String vendor, String oidKey) {
        return VENDOR_SPECIFIC_OIDS.getOrDefault(vendor,
                VENDOR_SPECIFIC_OIDS.get("Generic")).get(oidKey);
    }

    /**
     * Get all known printer vendors
     *
     * @return Array of vendor names
     */
    public static String[] getAllKnownVendors() {
        return VENDOR_SPECIFIC_OIDS.keySet().toArray(new String[0]);
    }
}