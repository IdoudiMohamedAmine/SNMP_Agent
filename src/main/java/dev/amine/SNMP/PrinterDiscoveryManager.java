package dev.amine.SNMP;

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

@Slf4j
@RequiredArgsConstructor
public class PrinterDiscoveryManager {
    private final ExecutorService scanExecutor;

    public PrinterDiscoveryManager() {
        this.scanExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2
        );
    }

    public List<PrinterDevice> discoverPrinters(String subnet) {
        List<PrinterDevice> discoveredPrinters = new CopyOnWriteArrayList<>();

        List<Callable<PrinterDevice>> scanTasks = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            final String ip = subnet + "." + i;
            scanTasks.add(() -> scanPrinterHost(ip));
        }

        try {
            List<Future<PrinterDevice>> results = scanExecutor.invokeAll(
                    scanTasks, 300, TimeUnit.SECONDS
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

    private PrinterDevice getResultSafely(Future<PrinterDevice> future) {
        try {
            return future.get();
        } catch (Exception e) {
            log.debug("Error retrieving printer scan result", e);
            return null;
        }
    }

    private PrinterDevice scanPrinterHost(String ipAddress) {
        for (String communityString : PrinterDiscoveryConfig.COMMUNITY) {
            try (TransportMapping<UdpAddress> transport = new DefaultUdpTransportMapping()) {
                Snmp snmp = new Snmp(transport);
                transport.listen();

                CommunityTarget target = createSnmpTarget(ipAddress, communityString);
                PDU pdu = createDetailedDiscoveryPDU();

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

    private CommunityTarget createSnmpTarget(String ipAddress, String communityString) {
        CommunityTarget target = new CommunityTarget();
        target.setCommunity(new OctetString(communityString));
        target.setAddress(new UdpAddress(ipAddress + "/161"));
        target.setRetries(1);
        target.setTimeout(1000);
        target.setVersion(SnmpConstants.version2c);
        return target;
    }

    private PDU createDetailedDiscoveryPDU() {
        PDU pdu = new PDU();
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_MODEL_NAME)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SYSTEM_DESCRIPTION)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_SERIAL_NUMBER)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.MAC_ADDRESS)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_STATUS)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_PAGE_COUNT)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.BLACK_TONER_LEVEL)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.CYAN_TONER_LEVEL)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.MAGENTA_TONER_LEVEL)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.YELLOW_TONER_LEVEL)));
        pdu.setType(PDU.GET);
        return pdu;
    }

    private boolean isValidPrinterResponse(ResponseEvent response) {
        return response != null
                && response.getResponse() != null
                && !response.getResponse().getVariableBindings().isEmpty();
    }

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
                    String[] serialParts = value.split(";");
                    if (serialParts.length > 1) {
                        deviceBuilder.productNumber(serialParts[0].trim());
                        deviceBuilder.serialNumber(serialParts[1].trim());
                    }
                    deviceBuilder.printerSerialNumber(value);
                    break;
                case PrinterDiscoveryConfig.MAC_ADDRESS:
                    deviceBuilder.printerMacAddress(formatMacAddress(value));
                    break;
                case PrinterDiscoveryConfig.PRINTER_STATUS:
                    deviceBuilder.printerStatus(mapPrinterStatus(value));
                    break;
                case PrinterDiscoveryConfig.PRINTER_PAGE_COUNT:
                    deviceBuilder.printerTotalPrintedPages(Integer.parseInt(value));
                    break;
                case PrinterDiscoveryConfig.BLACK_TONER_LEVEL:
                    deviceBuilder.blackTonerLevel(Integer.parseInt(value));
                    break;
                case PrinterDiscoveryConfig.CYAN_TONER_LEVEL:
                    deviceBuilder.cyanTonerLevel(Integer.parseInt(value));
                    break;
                case PrinterDiscoveryConfig.MAGENTA_TONER_LEVEL:
                    deviceBuilder.magentaTonerLevel(Integer.parseInt(value));
                    break;
                case PrinterDiscoveryConfig.YELLOW_TONER_LEVEL:
                    deviceBuilder.yellowTonerLevel(Integer.parseInt(value));
                    break;
            }
        }

        return deviceBuilder.build();
    }

    private String formatMacAddress(String macValue) {
        if (macValue.length() >= 6) {
            byte[] macBytes = macValue.getBytes();
            StringBuilder formattedMac = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                formattedMac.append(String.format("%02X", macBytes[i]));
                if (i < 5) formattedMac.append(":");
            }
            return formattedMac.toString();
        }
        return macValue;
    }

    private PrinterStatus mapPrinterStatus(String statusValue) {
        try {
            int status = Integer.parseInt(statusValue);
            switch (status) {
                case 1: return PrinterStatus.ONLINE;
                case 2: return PrinterStatus.OFFLINE;
                case 3: return PrinterStatus.LOW_SUPPLIES;
                case 4: return PrinterStatus.ERROR;
                default: return PrinterStatus.UNKNOWN;
            }
        } catch (NumberFormatException e) {
            return PrinterStatus.UNKNOWN;
        }
    }
}