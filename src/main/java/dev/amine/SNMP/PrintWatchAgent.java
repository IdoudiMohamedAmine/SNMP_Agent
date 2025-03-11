package dev.amine.SNMP;

import lombok.extern.slf4j.Slf4j;
import java.util.List;

@Slf4j
public class PrintWatchAgent {
    public static void main(String[] args) {
        String subnet = args.length > 0 ? args[0] : "192.168.0";
        log.info("Starting network scan for printers on {}...", subnet);

        PrinterDiscoveryManager manager = new PrinterDiscoveryManager();
        List<PrinterDevice> printers = manager.discoverPrinters(subnet);

        log.info("\nDiscovered {} printers:", printers.size());
        printers.forEach(PrintWatchAgent::printPrinterDetails);
    }

    private static void printPrinterDetails(PrinterDevice printer) {
        log.info("\n========== Printer Details ==========");
        log.info("IP Address:     {}", printer.getIpAddress());
        log.info("MAC Address:    {}", printer.getMacAddress() != null ? printer.getMacAddress() : "Unknown");
        log.info("Model:          {}", printer.getModelName() != null ? printer.getModelName() : "Unknown");
        log.info("Serial Number:  {}", printer.getSerialNumber() != null ? printer.getSerialNumber() : "Unknown");
        log.info("Vendor:         {}", printer.getVendor() != null ? printer.getVendor() : "Unknown");
        log.info("Status:         {}", printer.getStatus());

        // Page counts
        log.info("\n--- Page Counts ---");
        log.info("Total Pages:    {}", printer.getTotalPageCount() != null ?
                printer.getTotalPageCount().toString() : "Unknown");
        log.info("Color Pages:    {}", printer.getColorPageCount() != null ?
                printer.getColorPageCount().toString() : "Unknown");
        log.info("Mono Pages:     {}", printer.getMonoPageCount() != null ?
                printer.getMonoPageCount().toString() : "Unknown");

        // Supplies (toner, etc.)
        if (!printer.getSupplyLevels().isEmpty()) {
            log.info("\n--- Supplies ---");
            for (String supply : printer.getSupplyDescriptions().keySet()) {
                Integer percentage = printer.getSupplyPercentage(supply);
                String level = percentage != null ? percentage + "%" : "Unknown";
                log.info("{}: {}", supply, level);
            }
        }

        // Paper trays
        if (!printer.getTrayLevels().isEmpty()) {
            log.info("\n--- Paper Trays ---");
            for (String tray : printer.getTrayDescriptions().keySet()) {
                Integer percentage = printer.getTrayPercentage(tray);
                String level = percentage != null ? percentage + "%" : "Unknown";
                log.info("{}: {}", tray, level);
            }
        }

        log.info("=====================================\n");
    }
}