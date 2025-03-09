package dev.amine.SNMP;

import lombok.extern.slf4j.Slf4j;
import java.util.List;

@Slf4j
public class PrintWatchAgent {
    public static void main(String[] args) {
        String subnet = args.length > 0 ? args[0] : "192.168.0";
        log.info("Scanning {}...", subnet);

        PrinterDiscoveryManager manager = new PrinterDiscoveryManager();
        List<PrinterDevice> printers = manager.discoverPrinters(subnet);

        log.info("Found {} printers", printers.size());
        printers.forEach(p -> log.info("Printer at {}: {} ({})",
                p.getIpAddress(), p.getModelName(), p.getMacAddress()));
    }
}