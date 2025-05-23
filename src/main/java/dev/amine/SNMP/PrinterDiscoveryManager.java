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
    private static final ExecutorService executor = Executors.newFixedThreadPool(50);

    private static boolean isShutdown=false;

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
        } catch(RejectedExecutionException e){
            log.error("task rejected , executor is shutdown :{}",e.getMessage());
            return Collections.emptyList();
        }
    }
    public static void shutDown(){
        isShutdown=true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
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
                    getVendorSpecificInfo(snmp, target, device);
                    getTonerInfo(snmp, target, device);
                    getPaperTrayInfo(snmp, target, device);
                    determinePrinterType(device);
                    getMediaSizes(snmp, target, device);
                    return device;
                }
            }
        } catch (IOException e) {
            log.debug("Scan error {}: {}", ip, e.getMessage());
        }
        return null;
    }

    private void determinePrinterType(PrinterDevice device) {
        // Enhanced vendor detection
        String model = device.getModelName().toLowerCase();

        if (model.contains("versalink")) {
            device.setVendor("Xerox");
        } else if (model.contains("konica")) {
            device.setVendor("Konica Minolta");
        } else if (model.contains("hp") || model.contains("laserjet")) {
            device.setVendor("HP");
        } else if (model.contains("xerox")) {
            device.setVendor("Xerox");
        } else {
            device.setVendor("Unknown");
        }

        // Improved color detection
        boolean hasColorSupplies = device.getSupplyDescriptions().values().stream()
                .anyMatch(desc -> desc.toLowerCase().matches(".*\\b(cyan|magenta|yellow)\\b.*"));
        device.setColorPrinter(hasColorSupplies);
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
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SYSTEM_NAME)));
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

            try {
                if (oid.equals(PrinterDiscoveryConfig.SYSTEM_NAME)) {
                    device.setModelName(variable.toString());
                    isPrinter = true;
                } else if (oid.equals(PrinterDiscoveryConfig.SERIAL_NUMBER)) {
                    device.setSerialNumber(variable.toString());
                } else if (oid.equals(PrinterDiscoveryConfig.MAC_ADDRESS)) {
                    device.setMacAddress(formatMac(variable));
                } else if (oid.equals(PrinterDiscoveryConfig.TOTAL_PAGE_COUNT)) {
                    device.setTotalPageCount(Long.parseLong(variable.toString()));
                    isPrinter = true;
                } else if (oid.equals(PrinterDiscoveryConfig.PRINTER_STATUS)) {
                    int statusValue = Integer.parseInt(variable.toString());
                    device.setStatus(PrinterStatus.fromStatusValue(statusValue));
                }
            } catch (NumberFormatException e) {
                log.debug("Error parsing value for {}: {}", oid, variable.toString());
            }
        }
        return isPrinter;
    }

    private void getVendorSpecificInfo(Snmp snmp, CommunityTarget target, PrinterDevice device) {
        try {
            String vendor = device.getVendor();
            // Only perform vendor-specific queries if vendor is known
            if (vendor == null || vendor.equals("Unknown")) {
                return;
            }

            // Try retrieving the printer model using vendor-specific OID
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

            // If color and mono counts are missing but total is available, estimate values
            if ((device.getColorPageCount() == null || device.getMonoPageCount() == null) &&
                    device.getTotalPageCount() != null) {
                if (device.getMonoPageCount() == null) {
                    device.setMonoPageCount(device.getTotalPageCount());
                }
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

                    if (level < 0) level = 0;
                    if (max <= 0) max = 100;

                    device.getSupplyDescriptions().put(desc, desc);
                    device.getSupplyLevels().put(desc, level);
                    device.getSupplyMaxLevels().put(desc, max);
                } catch (Exception e) {
                    log.debug("Error parsing toner values for {}: {}", desc, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Error getting toner info for {}: {}", device.getIpAddress(), e.getMessage());
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

                    if (level < -1) level = 0;
                    if (max <= 0) max = 100;

                    device.getTrayDescriptions().put(desc, desc);
                    device.getTrayLevels().put(desc, level);
                    device.getTrayMaxLevels().put(desc, max);
                } catch (Exception e) {
                    log.debug("Error parsing paper tray values for {}: {}", desc, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Error getting paper tray info for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }

    private String formatMac(Variable variable) {
        if (variable == null) return null;

        try {
            if (variable instanceof OctetString) {
                byte[] macBytes = ((OctetString) variable).toByteArray();
                if (macBytes.length < 6) return null;
                int start = macBytes.length - 6; // Always take last 6 bytes
                return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                        macBytes[start] & 0xFF,
                        macBytes[start + 1] & 0xFF,
                        macBytes[start + 2] & 0xFF,
                        macBytes[start + 3] & 0xFF,
                        macBytes[start + 4] & 0xFF,
                        macBytes[start + 5] & 0xFF);
            }
            return variable.toString();
        } catch (Exception e) {
            log.error("MAC format error: {}", e.getMessage());
            return null;
        }
    }
    private void getMediaSizes(Snmp snmp, CommunityTarget target, PrinterDevice device) {
        try {
            TableUtils utils = new TableUtils(snmp, new DefaultPDUFactory());
            OID[] columns = {
                    new OID(PrinterDiscoveryConfig.MEDIA_SIZE_SUPPORTED)
            };

            List<TableEvent> events = utils.getTable(target, columns, null, null);
            for (TableEvent event : events) {
                if (event.isError()) continue;

                VariableBinding[] vbs = event.getColumns();
                if (vbs.length < 1) continue;

                if (vbs[0].getVariable().toString().equals("noSuchObject")) {
                    continue;
                }

                String mediaSize = vbs[0].getVariable().toString().trim();
                if (!mediaSize.isEmpty()) {
                    device.getSupportedMediaSizes().add(mediaSize);
                }
            }

            // If we couldn't get supported media sizes through SNMP,
            // check the tray names as a fallback
            if (device.getSupportedMediaSizes().isEmpty()) {
                device.getTrayDescriptions().forEach((name, desc) -> {
                    String trayInfo = (name + " " + desc).toLowerCase();
                    if (trayInfo.contains("a3")) {
                        device.getSupportedMediaSizes().add("a3");
                    }
                    if (trayInfo.contains("a4")) {
                        device.getSupportedMediaSizes().add("a4");
                    }
                });
            }
        } catch (Exception e) {
            log.debug("Error getting media sizes for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }
}