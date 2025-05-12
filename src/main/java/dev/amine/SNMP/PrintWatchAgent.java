package dev.amine.SNMP;

import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class PrintWatchAgent {
    private static final DatabaseManager dbManager = new DatabaseManager();
    private static PrinterDiscoveryManager discoveryManager = new PrinterDiscoveryManager();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    public static void main(String[] args) {
        String subnet = autoDetectSubnet();
        if (args.length > 0) {
            subnet = args[0];
        }
        log.info("Starting network scan for printers on {}...", subnet);
        final String fiinalSubnet = subnet;
        scheduler.scheduleAtFixedRate(() -> ScanAndProcessPrinters(fiinalSubnet), 0, 6, TimeUnit.HOURS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down PrintWatchAgent...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            log.info("PrintWatchAgent shutdown complete.");
        }));
    }

    private static void ScanAndProcessPrinters(String subnet) {
        try {
            log.info("Scanning subnet {} for printers...", subnet);
            List<PrinterDevice> printers=discoveryManager.discoverPrinters(subnet);
            if (printers.isEmpty()) {
                log.info("No printers found on the network.");
                return;
            }
            log.info("\nDiscovered {} printer(s):", printers.size());
            printers.forEach(printer -> {
                try {
                    UUID printerId = dbManager.upsertPrinter(printer);
                    if (printerId != null) {
                        dbManager.insertCounts(printerId, printer);
                        dbManager.insertSuppliesAndTrays(printerId, printer);
                        dbManager.insertAlerts(printerId, getPrinterAlerts(printer));
                    }
                } catch (Exception e) {
                    log.warn("Database update failed for {}: {}", printer.getIpAddress(), e.getMessage());
                }
                printPrinterDetails(printer);
            });

            checkForDatabaseAlerts();
            log.info("Scan complete. Next scan scheduled in 6 hours.");
        } catch (Exception e) {
            log.error("Error during printer scan: {}", e.getMessage(), e);
        }
    }

    // The rest of the file remains unchanged
    private static String autoDetectSubnet() {
        try {
            List<String> subnets = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()
                        || networkInterface.isVirtual() || networkInterface.isPointToPoint()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();

                    if (address instanceof Inet4Address) {
                        String ipAddress = address.getHostAddress();
                        String[] octets = ipAddress.split("\\.");
                        if (octets.length == 4) {
                            // Get first three octets as subnet
                            String subnet = octets[0] + "." + octets[1] + "." + octets[2];
                            // Ignore local and special subnets
                            if (!subnet.startsWith("127.") && !subnet.startsWith("169.254")) {
                                subnets.add(subnet);
                            }
                        }
                    }
                }
            }

            // Return the first detected subnet or a default
            return subnets.isEmpty() ? "192.168.1" : subnets.get(0);
        } catch (Exception e) {
            log.error("Error detecting subnet: {}", e.getMessage(), e);
            return "192.168.1"; // Default subnet if detection fails
        }
    }

    private static List<String> getPrinterAlerts(PrinterDevice printer) {
        List<String> alerts = new ArrayList<>();
        if (printer.getStatus() == PrinterStatus.DOWN) {
            alerts.add("ALERT: Printer is DOWN - " + printer.getIpAddress());
        } else if (printer.getStatus() == PrinterStatus.WARNING) {
            alerts.add("ALERT: Printer has WARNING status - " + printer.getIpAddress());
        }
        if (printer.isLowToner()) {
            alerts.add("ALERT: Low toner detected - " + printer.getIpAddress());
        }
        if (printer.isLowPaper()) {
            alerts.add("ALERT: Low paper detected - " + printer.getIpAddress());
        }
        return alerts;
    }

    private static void printPrinterDetails(PrinterDevice printer) {
        log.info("\n========== PRINTER DETAILS ==========");
        log.info("--- BASIC INFORMATION ---");
        log.info("IP Address:     {}", nullSafe(printer.getIpAddress()));
        log.info("MAC Address:    {}", formatMac(printer.getMacAddress()));
        log.info("Model:          {}", nullSafe(printer.getModelName()));
        log.info("Vendor:         {}", nullSafe(printer.getVendor()));
        log.info("Serial:         {}", nullSafe(printer.getSerialNumber()));
        log.info("Type:           {}", printer.isColorPrinter() ? "Color" : "Monochrome");
        log.info("Status:         {}", printer.getStatus());

        logPageCounts(printer);
        logSupplies(printer);
        logTrays(printer);

        log.info("=====================================\n");
    }

    private static void logPageCounts(PrinterDevice printer) {
        log.info("\n--- PAGE COUNTS ---");
        log.info("Total Pages:    {}", nullSafeLong(printer.getTotalPageCount()));
        log.info("Color Pages:    {}", nullSafeLong(printer.getColorPageCount()));
        log.info("Mono Pages:     {}", nullSafeLong(printer.getMonoPageCount()));
    }

    private static void logSupplies(PrinterDevice printer) {
        if (!printer.getSupplyLevels().isEmpty()) {
            log.info("\n--- SUPPLIES ---");
            printer.getSupplyDescriptions().forEach((name, desc) -> {
                Integer current = printer.getSupplyLevels().get(name);
                Integer max = printer.getSupplyMaxLevels().get(name);
                Integer percent = printer.getSupplyPercentage(name);

                log.info("Supply: {}", name);
                log.info("  - Current/Max: {}/{}", nullSafeInt(current), nullSafeInt(max));
                log.info("  - Percentage:  {}%", percent != null ? percent : "N/A");
                log.info("  - Status:      {}", getSupplyStatus(percent));
                log.info("------------------");
            });
        } else {
            log.info("\n--- SUPPLIES ---");
            log.info("No supply information available");
        }
    }

    private static void logTrays(PrinterDevice printer) {
        if (!printer.getTrayLevels().isEmpty()) {
            log.info("\n--- PAPER TRAYS ---");
            printer.getTrayDescriptions().forEach((name, desc) -> {
                Integer current = printer.getTrayLevels().get(name);
                Integer max = printer.getTrayMaxLevels().get(name);
                Integer percent = printer.getTrayPercentage(name);

                log.info("Tray: {}", name);
                log.info("  - Current/Max: {}/{} sheets", nullSafeInt(current), nullSafeInt(max));
                log.info("  - Percentage:  {}%", percent != null ? percent : "N/A");
                log.info("  - Status:      {}", getTrayStatus(percent));
                log.info("------------------");
            });
        } else {
            log.info("\n--- PAPER TRAYS ---");
            log.info("No tray information available");
        }
    }

    private static void checkForDatabaseAlerts() {
        log.info("\n=== Current Database Alerts ===");
        try {
            List<String> activeAlerts = dbManager.getActiveAlerts();
            if (activeAlerts.isEmpty()) {
                log.info("No unresolved alerts in database");
            } else {
                activeAlerts.forEach(alert -> log.info(" - {}", alert));
            }
        } catch (Exception e) {
            log.error("Error retrieving alerts: {}", e.getMessage());
        }
    }

    // Utility methods
    private static String nullSafe(String value) {
        return value != null ? value : "Not Available";
    }

    private static String nullSafeLong(Long value) {
        return value != null ? value.toString() : "Not Available";
    }

    private static String nullSafeInt(Integer value) {
        return value != null ? value.toString() : "N/A";
    }

    private static String formatMac(String mac) {
        if (mac == null) return "Not Available";
        return mac.replaceAll("(.{2})(?!$)", "$1:");
    }

    private static String getSupplyStatus(Integer percent) {
        if (percent == null) return "Unknown";
        if (percent <= 10) return "LOW - Replace immediately";
        if (percent <= 25) return "Warning - Monitor closely";
        return "OK";
    }

    private static String getTrayStatus(Integer percent) {
        if (percent == null) return "Unknown";
        if (percent <= 10) return "LOW - Refill needed";
        if (percent <= 30) return "Warning - Low paper";
        return "OK";
    }
}