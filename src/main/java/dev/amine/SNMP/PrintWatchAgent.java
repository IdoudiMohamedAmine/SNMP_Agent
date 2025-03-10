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
        log.info("\n=== Printer Details ===");
        log.info("IP Address: {}", printer.getIpAddress());
        log.info("MAC Address: {}", printer.getMacAddress());
        log.info("Model: {}", printer.getModelName());
        log.info("Serial Number: {}", printer.getSerialNumber());
        log.info("Status: {}", printer.getStatus());
        log.info("Total Pages Printed: {}", printer.getTotalPageCount());
        log.info("Color Pages: {}", printer.getColorPageCount());
        log.info("Monochrome Pages: {}", printer.getMonoPageCount());

        log.info("\nToner Levels:");
        printer.getSupplyLevels().forEach((name, level) -> {
            Integer max = printer.getSupplyMaxLevels().get(name);
            Integer percent = printer.getSupplyPercentage(name);
            String percentDisplay = (percent != null) ? percent + "%" : "N/A";
            log.info(" - {}: {} ({} of {})", name, percentDisplay, level, max);
        });

        log.info("\nPaper Trays:");
        printer.getTrayLevels().forEach((name, level) -> {
            Integer max = printer.getTrayMaxLevels().get(name);
            Integer percent = printer.getTrayPercentage(name);
            String percentDisplay = (percent != null) ? percent + "%" : "N/A";
            log.info(" - {}: {} ({} of {})", name, percentDisplay, level, max);
        });

        log.info("=======================");
    }
}