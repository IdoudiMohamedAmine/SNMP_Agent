package dev.amine.SNMP;



import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class PrintWatchAgent {
    public static void main(String[] args) {
        String subnet = args.length > 0 ? args[0] : "192.168.1";

        PrinterDiscoveryManager discoveryManager = new PrinterDiscoveryManager();
        List<PrinterDevice> printers = discoveryManager.discoverPrinters(subnet);

        log.info("Discovered {} printers", printers.size());
        printers.forEach(printer -> log.info(printer.toString()));
    }
}
