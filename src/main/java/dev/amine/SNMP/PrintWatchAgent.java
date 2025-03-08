package dev.amine.SNMP;

import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;

@Slf4j
public class PrintWatchAgent {
    public static void main(String[] args) {
        String subnet = args.length > 0 ? args[0] : "192.168.0";

        log.info("Scanning network {} for printers...", subnet);
        PrinterDiscoveryManager discoveryManager = new PrinterDiscoveryManager();
        List<PrinterDevice> printers = discoveryManager.discoverPrinters(subnet);

        log.info("Discovered {} printers", printers.size());

        for (PrinterDevice printer : printers) {
            log.info("---------------------------------------------");
            log.info("Printer: {} ({})", printer.getModelName(), printer.getIpAddress());
            log.info("MAC Address: {}", printer.getMacAddress());
            log.info("Location: {}", printer.getSystemLocation());
            log.info("Status: {} - {}", printer.getStatus(), printer.getStatusMessage());
            log.info("Total Page Count: {}", printer.getTotalPageCount());

            log.info("Supplies:");
            for (Map.Entry<String, Integer> supply : printer.getSupplyLevels().entrySet()) {
                String supplyName = supply.getKey();
                Integer level = supply.getValue();
                Integer maxLevel = printer.getSupplyMaxLevels().get(supplyName);
                String description = printer.getSupplyDescriptions().get(supplyName);

                Integer percentage = printer.getSupplyPercentage(supplyName);
                if (percentage != null) {
                    log.info(" - {}: {}% ({}/{})",
                            supplyName,
                            percentage,
                            level,
                            maxLevel);
                } else {
                    log.info(" - {}: {}", supplyName, level);
                }

                if (description != null) {
                    log.info("   Description: {}", description);
                }
            }

            log.info("System Description: {}", printer.getSystemDescription());
            if (printer.getConsoleMessage() != null && !printer.getConsoleMessage().isEmpty()) {
                log.info("Console Message: {}", printer.getConsoleMessage());
            }
        }
    }
}