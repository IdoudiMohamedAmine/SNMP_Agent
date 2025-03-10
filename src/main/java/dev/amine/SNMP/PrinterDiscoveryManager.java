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
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_MODEL)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SERIAL_NUMBER)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.MAC_ADDRESS)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.TOTAL_PAGE_COUNT)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.COLOR_PAGE_COUNT)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.MONO_PAGE_COUNT)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_STATUS)));
        pdu.setType(PDU.GET);

        ResponseEvent response = snmp.send(pdu, target);
        if (response == null || response.getResponse() == null) return false;

        for (VariableBinding vb : response.getResponse().getVariableBindings()) {
            String oid = vb.getOid().toString();
            String value = vb.getVariable().toString();

            try {
                if (oid.equals(PrinterDiscoveryConfig.PRINTER_MODEL)) {
                    device.setModelName(value);
                } else if (oid.equals(PrinterDiscoveryConfig.SERIAL_NUMBER)) {
                    device.setSerialNumber(value);
                } else if (oid.equals(PrinterDiscoveryConfig.MAC_ADDRESS)) {
                    device.setMacAddress(formatMac(vb.getVariable()));
                } else if (oid.equals(PrinterDiscoveryConfig.TOTAL_PAGE_COUNT)) {
                    device.setTotalPageCount(Long.parseLong(value));
                } else if (oid.equals(PrinterDiscoveryConfig.COLOR_PAGE_COUNT)) {
                    device.setColorPageCount(Long.parseLong(value));
                } else if (oid.equals(PrinterDiscoveryConfig.MONO_PAGE_COUNT)) {
                    device.setMonoPageCount(Long.parseLong(value));
                } else if (oid.equals(PrinterDiscoveryConfig.PRINTER_STATUS)) {
                    int statusValue = Integer.parseInt(value);
                    device.setStatus(PrinterStatus.fromStatusValue(statusValue));
                }
            } catch (NumberFormatException e) {
                log.debug("Error parsing numeric value for {}: {}", oid, value);
            }
        }
        return device.getModelName() != null;
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
                String desc = vbs[0].getVariable().toString();
                int level = vbs[1].getVariable().toInt();
                int max = vbs[2].getVariable().toInt();

                device.getSupplyDescriptions().put(desc, desc);
                device.getSupplyLevels().put(desc, level);
                device.getSupplyMaxLevels().put(desc, max);
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
                String desc = vbs[0].getVariable().toString();
                int level = vbs[1].getVariable().toInt();
                int max = vbs[2].getVariable().toInt();

                device.getTrayDescriptions().put(desc, desc);
                device.getTrayLevels().put(desc, level);
                device.getTrayMaxLevels().put(desc, max);
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