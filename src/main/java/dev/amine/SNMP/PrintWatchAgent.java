package dev.amine.SNMP;

import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

@Slf4j
public class PrintWatchAgent {
    public static void main(String[] args) {
        String subnet = args.length > 0 ? args[0] : "192.168.0";
        DatabaseManager dbManager = new DatabaseManager();
        log.info("Starting network scan for printers on {}...", subnet);

        PrinterDiscoveryManager manager = new PrinterDiscoveryManager();
        List<PrinterDevice> printers = manager.discoverPrinters(subnet);

        if (printers.isEmpty()) {
            log.info("No printers found on the network.");
            return;
        }

        log.info("\nDiscovered {} printer(s):", printers.size());
        printers.forEach(printer -> {
            // Validate counters before insertion
            boolean validCounters = dbManager.checkCounterIncrease(
                    printer.getMacAddress(),
                    printer.getTotalPageCount(),
                    printer.getColorPageCount(),
                    printer.getMonoPageCount()
            );

            if (validCounters) {
                dbManager.insertPrinterData(printer);
            } else {
                log.warn("Invalid counters for {} - possible reset detected", printer.getMacAddress());
            }

            printPrinterDetails(printer);
        });

        // Check for alerts
        checkForAlerts(printers);

        // Interactive mode
        interactiveMode(printers);
    }

    // Rest of the class remains unchanged below this point
    private static void checkForAlerts(List<PrinterDevice> printers) {
        log.info("\n=== Alerts ===");
        boolean hasAlerts = false;

        for (PrinterDevice printer : printers) {
            StringBuilder alerts = new StringBuilder();

            if (printer.getStatus() == PrinterStatus.DOWN) {
                alerts.append("ALERT: Printer is DOWN\n");
                hasAlerts = true;
            } else if (printer.getStatus() == PrinterStatus.WARNING) {
                alerts.append("ALERT: Printer has a WARNING status\n");
                hasAlerts = true;
            }

            if (printer.isLowToner()) {
                alerts.append("ALERT: Low toner detected\n");
                hasAlerts = true;
            }

            if (printer.isLowPaper()) {
                alerts.append("ALERT: Low paper detected\n");
                hasAlerts = true;
            }

            if (alerts.length() > 0) {
                log.info("Printer: {} ({})", printer.getModelName() != null ? printer.getModelName() : "Unknown", printer.getIpAddress());
                log.info(alerts.toString());
            }
        }

        if (!hasAlerts) {
            log.info("No alerts detected. All printers operating normally.");
        }
    }

    private static void interactiveMode(List<PrinterDevice> printers) {
        Scanner scanner = new Scanner(System.in);
        log.info("\nEnter a command (help, detail <index>, exit):");

        String command = "";
        while (!command.equalsIgnoreCase("exit")) {
            System.out.print("> ");
            command = scanner.nextLine();

            if (command.equalsIgnoreCase("help")) {
                log.info("Available commands:");
                log.info("  list         - Lists all discovered printers");
                log.info("  detail <num> - Shows detailed info for printer at index <num>");
                log.info("  alerts       - Shows all printer alerts");
                log.info("  rescan       - Rescans the network for printers");
                log.info("  exit         - Exits the program");
            } else if (command.equalsIgnoreCase("list")) {
                for (int i = 0; i < printers.size(); i++) {
                    PrinterDevice printer = printers.get(i);
                    log.info("{}: {} ({}) - {}",
                            i,
                            printer.getModelName() != null ? printer.getModelName() : "Unknown Model",
                            printer.getIpAddress(),
                            printer.getStatus());
                }
            } else if (command.startsWith("detail ")) {
                try {
                    int index = Integer.parseInt(command.substring(7).trim());
                    if (index >= 0 && index < printers.size()) {
                        printPrinterDetails(printers.get(index));
                    } else {
                        log.info("Invalid printer index. Use 'list' to see available printers.");
                    }
                } catch (NumberFormatException e) {
                    log.info("Invalid number format. Use 'detail <num>' with a valid number.");
                }
            } else if (command.equalsIgnoreCase("alerts")) {
                checkForAlerts(printers);
            } else if (command.equalsIgnoreCase("rescan")) {
                log.info("Rescanning network...");
                PrinterDiscoveryManager manager = new PrinterDiscoveryManager();
                printers = manager.discoverPrinters("192.168.0");
                log.info("Found {} printer(s)", printers.size());
            } else if (!command.equalsIgnoreCase("exit")) {
                log.info("Unknown command. Type 'help' for available commands.");
            }
        }

        log.info("Exiting...");
        scanner.close();
    }

    private static void printPrinterDetails(PrinterDevice printer) {
        log.info("\n========== PRINTER DETAILS ==========");

        // Basic Information Section
        log.info("--- BASIC INFORMATION ---");
        log.info("IP Address:     {}", printer.getIpAddress() != null ? printer.getIpAddress() : "Not Available");
        log.info("MAC Address:    {}", printer.getMacAddress() != null ? printer.getMacAddress() : "Not Available");
        log.info("Model:          {}", printer.getModelName() != null ? printer.getModelName() : "Not Available");
        log.info("Serial Number:  {}", printer.getSerialNumber() != null ? printer.getSerialNumber() : "Not Available");
        log.info("Vendor:         {}", printer.getVendor() != null ? printer.getVendor() : "Not Available");
        log.info("Status:         {}", printer.getStatus() != null ? printer.getStatus() : "UNKNOWN");

        // Page Counts Section
        log.info("\n--- PAGE COUNTS ---");
        log.info("Total Pages:    {}", printer.getTotalPageCount() != null ?
                printer.getTotalPageCount().toString() : "Not Available");
        log.info("Color Pages:    {}", printer.getColorPageCount() != null ?
                printer.getColorPageCount().toString() : "Not Available");
        log.info("Mono Pages:     {}", printer.getMonoPageCount() != null ?
                printer.getMonoPageCount().toString() : "Not Available");

        // Supplies Section (toner, ink, etc.)
        if (!printer.getSupplyLevels().isEmpty()) {
            log.info("\n--- SUPPLIES ---");
            for (String supply : printer.getSupplyDescriptions().keySet()) {
                Integer current = printer.getSupplyLevels().get(supply);
                Integer max = printer.getSupplyMaxLevels().get(supply);
                Integer percentage = printer.getSupplyPercentage(supply);

                log.info("Supply:         {}", supply);
                log.info("  - Level:      {}/{} units",
                        current != null ? current : "N/A",
                        max != null ? max : "N/A");
                log.info("  - Percentage: {}%",
                        percentage != null ? percentage : "Not Available");

                // Add warning indicator for low supplies
                if (percentage != null && percentage <= 10) {
                    log.info("  - Status:     LOW - Replacement recommended");
                } else if (percentage != null) {
                    log.info("  - Status:     OK");
                } else {
                    log.info("  - Status:     Unknown");
                }
                log.info("------------------");
            }
        } else {
            log.info("\n--- SUPPLIES ---");
            log.info("No supply information available");
        }

        // Paper Trays Section
        if (!printer.getTrayLevels().isEmpty()) {
            log.info("\n--- PAPER TRAYS ---");
            for (String tray : printer.getTrayDescriptions().keySet()) {
                Integer current = printer.getTrayLevels().get(tray);
                Integer max = printer.getTrayMaxLevels().get(tray);
                Integer percentage = printer.getTrayPercentage(tray);

                log.info("Tray:           {}", tray);
                log.info("  - Level:      {}/{} sheets",
                        current != null ? current : "N/A",
                        max != null ? max : "N/A");
                log.info("  - Percentage: {}%",
                        percentage != null ? percentage : "Not Available");

                // Add warning indicator for low paper
                if (percentage != null && percentage <= 10) {
                    log.info("  - Status:     LOW - Refill recommended");
                } else if (percentage != null) {
                    log.info("  - Status:     OK");
                } else {
                    log.info("  - Status:     Unknown");
                }
                log.info("------------------");
            }
        } else {
            log.info("\n--- PAPER TRAYS ---");
            log.info("No paper tray information available");
        }

        log.info("=====================================\n");
    }
}