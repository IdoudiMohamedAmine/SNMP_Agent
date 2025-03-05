package dev.amine.SNMP;

import dev.amine.SNMP.PrinterDevice;
import dev.amine.SNMP.PrinterDiscoveryConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages printer discovery and network scanning operations
 */
@Slf4j
@RequiredArgsConstructor
public class PrinterDiscoveryManager {
    // Configurable thread pool for concurrent scanning
    private final ExecutorService scanExecutor;

    /**
     * Default constructor with optimized thread pool
     */
    public PrinterDiscoveryManager() {
        this.scanExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2
        );
    }

    /**
     * Discovers printers across specified network subnet
     */
    public List<PrinterDevice> discoverPrinters(String subnet) {
        List<PrinterDevice> discoveredPrinters = new CopyOnWriteArrayList<>();

        List<Callable<PrinterDevice>> scanTasks = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            final String ip = subnet + "." + i;
            scanTasks.add(() -> scanPrinterHost(ip));
        }

        try {
            List<Future<PrinterDevice>> results = scanExecutor.invokeAll(
                    scanTasks, 2, TimeUnit.MINUTES
            );

            results.stream()
                    .map(this::getResultSafely)
                    .filter(Objects::nonNull)
                    .forEach(discoveredPrinters::add);

        } catch (InterruptedException e) {
            log.warn("Printer discovery process interrupted", e);
            Thread.currentThread().interrupt();
        }

        return discoveredPrinters;
    }

    /**
     * Safely retrieves Future result, handling potential exceptions
     */
    private PrinterDevice getResultSafely(Future<PrinterDevice> future) {
        try {
            return future.get();
        } catch (Exception e) {
            log.debug("Error retrieving printer scan result", e);
            return null;
        }
    }

    /**
     * Scans a specific IP address for printer characteristics
     */
    private PrinterDevice scanPrinterHost(String ipAddress) {
        for (String communityString : PrinterDiscoveryConfig.COMMUNITY) {
            try (TransportMapping<UdpAddress> transport = new DefaultUdpTransportMapping()) {
                Snmp snmp = new Snmp(transport);
                transport.listen();

                CommunityTarget target = createSnmpTarget(ipAddress, communityString);
                PDU pdu = createDiscoveryPDU();

                ResponseEvent response = snmp.send(pdu, target);
                if (isValidPrinterResponse(response)) {
                    return extractPrinterDevice(response, ipAddress);
                }
            } catch (IOException e) {
                log.trace("Communication error scanning {}", ipAddress);
            }
        }
        return null;
    }

    /**
     * Creates standard SNMP community target
     */
    private CommunityTarget createSnmpTarget(String ipAddress, String communityString) {
        CommunityTarget target = new CommunityTarget();
        target.setCommunity(new OctetString(communityString));
        target.setAddress(new UdpAddress(ipAddress + "/161"));
        target.setRetries(1);
        target.setTimeout(1000);
        target.setVersion(SnmpConstants.version2c);
        return target;
    }

    /**
     * Creates PDU for printer discovery
     */
    private PDU createDiscoveryPDU() {
        PDU pdu = new PDU();
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_MODEL_NAME)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SYSTEM_DESCRIPTION)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_SERIAL_NUMBER)));
        pdu.setType(PDU.GET);
        return pdu;
    }

    /**
     * Validates SNMP response for printer identification
     */
    private boolean isValidPrinterResponse(ResponseEvent response) {
        return response != null
                && response.getResponse() != null
                && !response.getResponse().getVariableBindings().isEmpty();
    }

    /**
     * Extracts printer device information from SNMP response
     */
    private PrinterDevice extractPrinterDevice(ResponseEvent response, String ipAddress) {
        PDU responsePDU = response.getResponse();
        PrinterDevice.PrinterDeviceBuilder deviceBuilder = PrinterDevice.builder()
                .ipAddress(ipAddress);

        for (VariableBinding vb : responsePDU.getVariableBindings()) {
            String oid = vb.getOid().toString();
            String value = vb.getVariable().toString();

            switch (oid) {
                case PrinterDiscoveryConfig.SYSTEM_DESCRIPTION:
                    deviceBuilder.systemDescription(value);
                    break;
                case PrinterDiscoveryConfig.PRINTER_MODEL_NAME:
                    deviceBuilder.printerModelName(value);
                    break;
                case PrinterDiscoveryConfig.PRINTER_SERIAL_NUMBER:
                    deviceBuilder.printerSerialNumber(value);
                    break;
            }
        }

        return deviceBuilder.build();
    }
}