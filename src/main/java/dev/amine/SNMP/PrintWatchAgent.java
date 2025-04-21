    package dev.amine.SNMP;
    
    import lombok.extern.slf4j.Slf4j;
    import java.util.*;
    import java.util.concurrent.TimeUnit;
    import java.util.stream.Collectors;
    
    @Slf4j
    public class PrintWatchAgent {
        private static final DatabaseManager dbManager = new DatabaseManager();
        private static PrinterDiscoveryManager discoveryManager = new PrinterDiscoveryManager();
    
        public static void main(String[] args) {
            String subnet = args.length > 0 ? args[0] : "192.168.0";
            log.info("Starting network scan for printers on {}...", subnet);
    
            List<PrinterDevice> printers = discoveryManager.discoverPrinters(subnet);
    
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
                        dbManager.upsertSupplies(printerId, printer);
                        dbManager.upsertTrays(printerId, printer);
                        dbManager.insertAlerts(printerId, getPrinterAlerts(printer));
                    }
                } catch (Exception e) {
                    log.warn("Database update failed for {}: {}", printer.getIpAddress(), e.getMessage());
                }
                printPrinterDetails(printer);
            });
    
            checkForDatabaseAlerts();
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