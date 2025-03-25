package dev.amine.SNMP;

import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class PrintWatchAgent {
    private static final int INITIAL_DELAY = 0;
    private static final int SCAN_INTERVAL = 60; // minutes
    private static final String SUBNET = "192.168.0"; // Change this to your subnet

    public static void main(String[] args) {
        log.info("Starting PrintWatch Agent - Hourly Scanning Mode");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // Add shutdown hook for proper termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down PrintWatch Agent...");
            scheduler.shutdown();
        }));

        // Schedule hourly scans
        scheduler.scheduleAtFixedRate(
                PrintWatchAgent::scanAndProcess,
                INITIAL_DELAY,
                SCAN_INTERVAL,
                TimeUnit.MINUTES
        );

        // Keep the main thread alive
        try {
            scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            log.error("Main thread interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private static void scanAndProcess() {
        log.info("Starting scheduled printer scan...");
        PrinterDiscoveryManager manager = new PrinterDiscoveryManager();
        List<PrinterDevice> printers = manager.discoverPrinters(SUBNET);
        processDiscoveredPrinters(printers);
    }

    private static void processDiscoveredPrinters(List<PrinterDevice> printers) {
        if (printers.isEmpty()) {
            log.info("No printers found in this scan.");
            return;
        }

        log.info("Discovered {} printer(s) in this scan:", printers.size());
        printers.forEach(printer -> {
            logPrinterSummary(printer);
            DatabaseManager.savePrinterData(printer);
        });

        checkForAlerts(printers);
    }

    private static void logPrinterSummary(PrinterDevice printer) {
        log.info("Printer: {} ({}), Pages: {}, Status: {}",
                printer.getModelName() != null ? printer.getModelName() : "Unknown",
                printer.getIpAddress(),
                printer.getTotalPageCount() != null ? printer.getTotalPageCount() : "N/A",
                printer.getStatus());
    }

    private static void checkForAlerts(List<PrinterDevice> printers) {
        printers.forEach(printer -> {
            boolean hasAlert = false;
            StringBuilder alertMessage = new StringBuilder();

            if (printer.getStatus() == PrinterStatus.DOWN) {
                alertMessage.append("Printer is DOWN. ");
                hasAlert = true;
            } else if (printer.getStatus() == PrinterStatus.WARNING) {
                alertMessage.append("Printer has WARNING status. ");
                hasAlert = true;
            }

            if (printer.isLowToner()) {
                alertMessage.append("Low toner detected. ");
                hasAlert = true;
            }

            if (printer.isLowPaper()) {
                alertMessage.append("Low paper detected. ");
                hasAlert = true;
            }

            if (hasAlert) {
                log.warn("ALERT - {} ({}): {}",
                        printer.getModelName() != null ? printer.getModelName() : "Unknown",
                        printer.getIpAddress(),
                        alertMessage.toString().trim());
            }
        });
    }
}