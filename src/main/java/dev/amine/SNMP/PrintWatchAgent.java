package dev.amine.SNMP;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

@Slf4j
public class PrintWatchAgent {
    private static DatabaseManager dbManager = new DatabaseManager();

    public static void main(String[] args) {
        String subnet = args.length > 0 ? args[0] : "192.168.0";
        log.info("Starting network scan for printers on {}...", subnet);

        PrinterDiscoveryManager manager = new PrinterDiscoveryManager();
        List<PrinterDevice> printers = manager.discoverPrinters(subnet);

        if (printers.isEmpty()) {
            log.info("No printers found on the network.");
            return;
        }

        log.info("\nDiscovered {} printer(s):", printers.size());
        printers.forEach(printer -> {
            UUID printerId = dbManager.upsertPrinter(printer);
            if (printerId != null) {
                dbManager.insertCounts(printerId, printer);
                dbManager.upsertSupplies(printerId, printer);
                dbManager.upsertTrays(printerId, printer);
                dbManager.insertAlerts(printerId, getPrinterAlerts(printer));
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

    // Rest of the class remains the same until interactiveMode

    private static void interactiveMode(List<PrinterDevice> printers) {
        Scanner scanner = new Scanner(System.in);
        log.info("\nEnter a command (help, detail <index>, exit):");

        while (true) {
            System.out.print("> ");
            String command = scanner.nextLine().trim();

            if (command.equalsIgnoreCase("exit")) {
                break;
            }

            handleCommand(command, printers);
        }

        log.info("Exiting...");
        scanner.close();
    }

    private static void handleCommand(String command, List<PrinterDevice> printers) {
        if (command.equalsIgnoreCase("help")) {
            printHelp();
        } else if (command.equalsIgnoreCase("list")) {
            listPrinters(printers);
        } else if (command.startsWith("detail ")) {
            showDetail(command, printers);
        } else if (command.equalsIgnoreCase("alerts")) {
            checkForDatabaseAlerts();
        } else if (command.equalsIgnoreCase("rescan")) {
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
                    printer.getModelName() != null ? printer.getModelName() : "Unknown Model",
                    printer.getIpAddress(),
                    printer.getStatus(),
                    printer.isColorPrinter() ? "Color" : "Monochrome");
        }
    }

    private static void showDetail(String command, List<PrinterDevice> printers) {
        try {
            int index = Integer.parseInt(command.substring(7).trim());
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
        PrinterDiscoveryManager manager = new PrinterDiscoveryManager();
        List<PrinterDevice> newPrinters = manager.discoverPrinters("192.168.0");

        newPrinters.forEach(printer -> {
            UUID printerId = dbManager.upsertPrinter(printer);
            if (printerId != null) {
                dbManager.insertCounts(printerId, printer);
                dbManager.upsertSupplies(printerId, printer);
                dbManager.upsertTrays(printerId, printer);
                dbManager.insertAlerts(printerId, getPrinterAlerts(printer));
            }
        });

        printers.clear();
        printers.addAll(newPrinters);
        log.info("Rescan completed. Found {} printer(s)", printers.size());
    }

    // Rest of the utility methods remain unchanged
    private static void printPrinterDetails(PrinterDevice printer) {
        log.info("\n========== PRINTER DETAILS ==========");
        log.info("--- BASIC INFORMATION ---");
        log.info("IP Address:     {}", printer.getIpAddress() != null ? printer.getIpAddress() : "Not Available");
        log.info("MAC Address:    {}", printer.getMacAddress() != null ? printer.getMacAddress() : "Not Available");
        log.info("Model:          {}", printer.getModelName() != null ? printer.getModelName() : "Not Available");
        log.info("Serial Number:  {}", printer.getSerialNumber() != null ? printer.getSerialNumber() : "Not Available");
        log.info("Vendor:         {}", printer.getVendor() != null ? printer.getVendor() : "Not Available");
        log.info("Type:           {}", printer.isColorPrinter() ? "Color Printer" : "Monochrome Printer");
        log.info("Status:         {}", printer.getStatus() != null ? printer.getStatus() : "UNKNOWN");

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

    private static String nullSafeLong(Long value) {
        return value != null ? value.toString() : "Not Available";
    }

    private static String nullSafeInt(Integer value) {
        return value != null ? value.toString() : "N/A";
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