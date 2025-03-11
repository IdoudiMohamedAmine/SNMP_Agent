package dev.amine.SNMP;

import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.*;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
public class PrinterDiscoveryManager {
    private final ExecutorService executor = Executors.newFixedThreadPool(50);

    public List<PrinterDevice> discoverPrinters(String subnet) {
        List<Callable<PrinterDevice>> tasks = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            String ip = subnet + "." + i;
            tasks.add(() -> scanPrinter(ip));
        }

        try {
            return executor.invokeAll(tasks, 5, TimeUnit.MINUTES).stream()
                    .map(this::getResult)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } finally {
            executor.shutdown(); // Ensure executor is shut down when done
        }
    }

    private PrinterDevice getResult(Future<PrinterDevice> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }

    private PrinterDevice scanPrinter(String ip) {
        try (TransportMapping<?> transport = new DefaultUdpTransportMapping()) {
            transport.listen();
            Snmp snmp = new Snmp(transport);

            for (String community : PrinterDiscoveryConfig.COMMUNITY) {
                PrinterDevice device = new PrinterDevice();
                device.setIpAddress(ip);

                CommunityTarget target = createTarget(ip, community);
                if (getBasicInfo(snmp, target, device)) {
                    // Try to determine vendor from model or system description
                    determineVendor(device);

                    // Get vendor-specific data
                    getVendorSpecificInfo(snmp, target, device);

                    // Get consumables and tray info
                    getTonerInfo(snmp, target, device);
                    getPaperTrayInfo(snmp, target, device);

                    return device;
                }
            }
        } catch (IOException e) {
            log.debug("Error scanning {}: {}", ip, e.getMessage());
        }
        return null;
    }

    private void determineVendor(PrinterDevice device) {
        String modelName = device.getModelName();
        if (modelName != null) {
            modelName = modelName.toLowerCase();
            for (String vendor : PrinterDiscoveryConfig.getAllKnownVendors()) {
                if (modelName.contains(vendor.toLowerCase())) {
                    device.setVendor(vendor);
                    return;
                }
            }
        }

        // Default to generic if no vendor detected
        device.setVendor("Generic");
    }

    private CommunityTarget createTarget(String ip, String community) {
        CommunityTarget target = new CommunityTarget();
        target.setCommunity(new OctetString(community));
        target.setAddress(new UdpAddress(ip + "/161"));
        target.setTimeout(1500);
        target.setRetries(1);
        target.setVersion(SnmpConstants.version2c);
        return target;
    }

    private boolean getBasicInfo(Snmp snmp, CommunityTarget target, PrinterDevice device) throws IOException {
        PDU pdu = new PDU();
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SYSTEM_DESCRIPTION)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SERIAL_NUMBER)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.MAC_ADDRESS)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.TOTAL_PAGE_COUNT)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_STATUS)));
        pdu.setType(PDU.GET);

        ResponseEvent response = snmp.send(pdu, target);
        if (response == null || response.getResponse() == null) return false;

        boolean isPrinter = false;

        for (VariableBinding vb : response.getResponse().getVariableBindings()) {
            String oid = vb.getOid().toString();
            Variable variable = vb.getVariable();

            // Skip "noSuchObject" responses
            if (variable.toString().equals("noSuchObject")) {
                continue;
            }

            try {
                if (oid.equals(PrinterDiscoveryConfig.SYSTEM_DESCRIPTION)) {
                    String sysDesc = variable.toString();
                    // If system description contains "printer", it's likely a printer
                    if (sysDesc.toLowerCase().contains("printer")) {
                        isPrinter = true;
                    }
                } else if (oid.equals(PrinterDiscoveryConfig.SERIAL_NUMBER)) {
                    device.setSerialNumber(variable.toString());
                    isPrinter = true; // If it has a serial number in printer OID, it's likely a printer
                } else if (oid.equals(PrinterDiscoveryConfig.MAC_ADDRESS)) {
                    device.setMacAddress(formatMac(variable));
                } else if (oid.equals(PrinterDiscoveryConfig.TOTAL_PAGE_COUNT)) {
                    device.setTotalPageCount(Long.parseLong(variable.toString()));
                    isPrinter = true; // If it has page count, it's definitely a printer
                } else if (oid.equals(PrinterDiscoveryConfig.PRINTER_STATUS)) {
                    int statusValue = Integer.parseInt(variable.toString());
                    device.setStatus(PrinterStatus.fromStatusValue(statusValue));
                    isPrinter = true; // If it has printer status, it's definitely a printer
                }
            } catch (NumberFormatException e) {
                log.debug("Error parsing numeric value for {}: {}", oid, variable.toString());
            }
        }

        return isPrinter;
    }

    private void getVendorSpecificInfo(Snmp snmp, CommunityTarget target, PrinterDevice device) {
        try {
            // Get vendor
            String vendor = device.getVendor();
            if (vendor == null) vendor = "Generic";

            // Try model name first
            String modelOid = PrinterDiscoveryConfig.getVendorSpecificOid(vendor, "PRINTER_MODEL");
            if (modelOid != null) {
                Variable modelVar = getSnmpValue(snmp, target, modelOid);
                if (modelVar != null && !modelVar.toString().equals("noSuchObject")) {
                    device.setModelName(modelVar.toString());
                }
            }

            // Try color page count
            String colorOid = PrinterDiscoveryConfig.getVendorSpecificOid(vendor, "COLOR_PAGE_COUNT");
            if (colorOid != null) {
                Variable colorVar = getSnmpValue(snmp, target, colorOid);
                if (colorVar != null && !colorVar.toString().equals("noSuchObject")) {
                    try {
                        device.setColorPageCount(Long.parseLong(colorVar.toString()));
                    } catch (NumberFormatException e) {
                        log.debug("Error parsing color page count: {}", colorVar.toString());
                    }
                }
            }

            // Try mono page count
            String monoOid = PrinterDiscoveryConfig.getVendorSpecificOid(vendor, "MONO_PAGE_COUNT");
            if (monoOid != null) {
                Variable monoVar = getSnmpValue(snmp, target, monoOid);
                if (monoVar != null && !monoVar.toString().equals("noSuchObject")) {
                    try {
                        device.setMonoPageCount(Long.parseLong(monoVar.toString()));
                    } catch (NumberFormatException e) {
                        log.debug("Error parsing mono page count: {}", monoVar.toString());
                    }
                }
            }

            // If we don't have color and mono but we have total, estimate
            if ((device.getColorPageCount() == null || device.getMonoPageCount() == null) &&
                    device.getTotalPageCount() != null) {
                // Assume all mono if we couldn't get color count
                if (device.getMonoPageCount() == null) {
                    device.setMonoPageCount(device.getTotalPageCount());
                }
                // Set color to 0 if we couldn't get it
                if (device.getColorPageCount() == null) {
                    device.setColorPageCount(0L);
                }
            }
        } catch (Exception e) {
            log.debug("Error getting vendor-specific info for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }

    private Variable getSnmpValue(Snmp snmp, CommunityTarget target, String oidString) {
        try {
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(oidString)));
            pdu.setType(PDU.GET);

            ResponseEvent response = snmp.send(pdu, target);
            if (response != null && response.getResponse() != null &&
                    !response.getResponse().getVariableBindings().isEmpty()) {
                return response.getResponse().getVariableBindings().get(0).getVariable();
            }
        } catch (IOException e) {
            log.debug("Error getting SNMP value for {}: {}", oidString, e.getMessage());
        }
        return null;
    }

    private void getTonerInfo(Snmp snmp, CommunityTarget target, PrinterDevice device) {
        try {
            TableUtils utils = new TableUtils(snmp, new DefaultPDUFactory());
            OID[] columns = {
                    new OID(PrinterDiscoveryConfig.TONER_DESCRIPTION),
                    new OID(PrinterDiscoveryConfig.TONER_LEVELS),
                    new OID(PrinterDiscoveryConfig.TONER_MAX_LEVELS)
            };

            List<TableEvent> events = utils.getTable(target, columns, null, null);
            for (TableEvent event : events) {
                if (event.isError()) continue;

                VariableBinding[] vbs = event.getColumns();
                if (vbs.length < 3) continue;

                // Skip if any values are "noSuchObject"
                if (vbs[0].getVariable().toString().equals("noSuchObject") ||
                        vbs[1].getVariable().toString().equals("noSuchObject") ||
                        vbs[2].getVariable().toString().equals("noSuchObject")) {
                    continue;
                }

                String desc = vbs[0].getVariable().toString().trim();
                if (desc.isEmpty()) continue;

                try {
                    int level = vbs[1].getVariable().toInt();
                    int max = vbs[2].getVariable().toInt();

                    // Some printers report -2 for "unknown" - handle appropriately
                    if (level < 0) level = 0;
                    if (max <= 0) max = 100; // Default to percentage if max is invalid

                    device.getSupplyDescriptions().put(desc, desc);
                    device.getSupplyLevels().put(desc, level);
                    device.getSupplyMaxLevels().put(desc, max);
                } catch (Exception e) {
                    log.debug("Error parsing toner values for {}: {}", desc, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Toner info failed for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }

    private void getPaperTrayInfo(Snmp snmp, CommunityTarget target, PrinterDevice device) {
        try {
            TableUtils utils = new TableUtils(snmp, new DefaultPDUFactory());
            OID[] columns = {
                    new OID(PrinterDiscoveryConfig.PAPER_TRAY_DESCRIPTION),
                    new OID(PrinterDiscoveryConfig.PAPER_TRAY_LEVELS),
                    new OID(PrinterDiscoveryConfig.PAPER_TRAY_MAX_LEVELS)
            };

            List<TableEvent> events = utils.getTable(target, columns, null, null);
            for (TableEvent event : events) {
                if (event.isError()) continue;

                VariableBinding[] vbs = event.getColumns();
                if (vbs.length < 3) continue;

                // Skip if any values are "noSuchObject"
                if (vbs[0].getVariable().toString().equals("noSuchObject") ||
                        vbs[1].getVariable().toString().equals("noSuchObject") ||
                        vbs[2].getVariable().toString().equals("noSuchObject")) {
                    continue;
                }

                String desc = vbs[0].getVariable().toString().trim();
                if (desc.isEmpty()) continue;

                try {
                    int level = vbs[1].getVariable().toInt();
                    int max = vbs[2].getVariable().toInt();

                    // Some printers report -3 for empty tray
                    // or other negative values for various conditions
                    if (level < 0) level = 0;
                    if (max <= 0) max = 100; // Default to percentage if max is invalid

                    device.getTrayDescriptions().put(desc, desc);
                    device.getTrayLevels().put(desc, level);
                    device.getTrayMaxLevels().put(desc, max);
                } catch (Exception e) {
                    log.debug("Error parsing paper tray values for {}: {}", desc, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Paper tray info failed for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }

    private String formatMac(Variable macVar) {
        if (macVar instanceof OctetString) {
            byte[] bytes = ((OctetString) macVar).getValue();
            if (bytes.length == 6) {
                return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5]);
            }
        }
        return "Unknown";
    }
}