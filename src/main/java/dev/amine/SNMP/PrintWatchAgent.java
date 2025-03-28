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
        interactiveMode(printers);
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

    private static void interactiveMode(List<PrinterDevice> printers) {
        Scanner scanner = new Scanner(System.in);
        log.info("\nEnter a command (help, detail <index>, exit):");

        while (true) {
            System.out.print("> ");
            String command = scanner.nextLine().trim().toLowerCase();

            if (command.equals("exit")) {
                break;
            }
            handleCommand(command, printers, scanner);
        }

        log.info("Exiting...");
        scanner.close();
    }

    private static void handleCommand(String command, List<PrinterDevice> printers, Scanner scanner) {
        if (command.equals("help")) {
            printHelp();
        } else if (command.equals("list")) {
            listPrinters(printers);
        } else if (command.startsWith("detail")) {
            handleDetailCommand(command, printers);
        } else if (command.equals("alerts")) {
            checkForDatabaseAlerts();
        } else if (command.equals("rescan")) {
            rescanNetwork(printers);
        } else {
            log.info("Unknown command. Type 'help' for available commands.");
        }
    }

    private static void printHelp() {
        log.info("Available commands:");
        log.info("  list         - Lists all discovered printers");
        log.info("  detail <num> - Shows detailed info for printer at index <num>");
        log.info("  alerts       - Shows all active alerts from database");
        log.info("  rescan       - Rescans the network and updates database");
        log.info("  exit         - Exits the program");
    }

    private static void listPrinters(List<PrinterDevice> printers) {
        for (int i = 0; i < printers.size(); i++) {
            PrinterDevice printer = printers.get(i);
            log.info("{}: {} ({}) - {} - {}",
                    i,
                    printer.getModelName(),
                    printer.getIpAddress(),
                    printer.getStatus(),
                    printer.isColorPrinter() ? "Color" : "Monochrome");
        }
    }

    private static void handleDetailCommand(String command, List<PrinterDevice> printers) {
        try {
            int index = Integer.parseInt(command.substring(6).trim());
            if (index >= 0 && index < printers.size()) {
                PrinterDevice printer = printers.get(index);
                printPrinterDetails(printer);
                log.info("\n--- Historical Data ---");
                dbManager.getHistoricalData(printer.getMacAddress(), printer.getModelName());
            } else {
                log.info("Invalid printer index. Use 'list' to see available printers.");
            }
        } catch (NumberFormatException e) {
            log.info("Invalid number format. Use 'detail <num>' with a valid number.");
        }
    }

    private static void rescanNetwork(List<PrinterDevice> printers) {
        log.info("Rescanning network...");
        List<PrinterDevice> newPrinters = discoveryManager.discoverPrinters("192.168.0");

        newPrinters.forEach(printer -> {
            try {
                UUID printerId = dbManager.upsertPrinter(printer);
                if (printerId != null) {
                    dbManager.insertCounts(printerId, printer);
                    dbManager.upsertSupplies(printerId, printer);
                    dbManager.upsertTrays(printerId, printer);
                    dbManager.insertAlerts(printerId, getPrinterAlerts(printer));
                }
            } catch (Exception e) {
                log.warn("Failed to update database for {}: {}", printer.getIpAddress(), e.getMessage());
            }
        });

        printers.clear();
        printers.addAll(newPrinters);
        log.info("Rescan completed. Found {} printer(s)", printers.size());
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