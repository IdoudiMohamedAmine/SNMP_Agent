package dev.amine.SNMP;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class PrinterDiscoveryManager {
    private final ExecutorService scanExecutor;

    public PrinterDiscoveryManager() {
        this.scanExecutor = Executors.newFixedThreadPool(50);
    }

    public List<PrinterDevice> discoverPrinters(String subnet) {
        List<Callable<PrinterDevice>> tasks = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            String ip = subnet + "." + i;
            tasks.add(() -> scanPrinter(ip));
        }

        try {
            return scanExecutor.invokeAll(tasks, 10, TimeUnit.MINUTES).stream()
                    .map(this::getResultSafely)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
    }

    private PrinterDevice getResultSafely(Future<PrinterDevice> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | CancellationException e) {
            log.debug("Scan task failed: {}", e.getMessage());
            return null;
        }
    }

    private PrinterDevice scanPrinter(String ip) {
        for (String community : PrinterDiscoveryConfig.COMMUNITY) {
            try (TransportMapping<?> transport = new DefaultUdpTransportMapping()) {
                transport.listen();
                Snmp snmp = new Snmp(transport);
                CommunityTarget target = createTarget(ip, community);

                PrinterDevice device = getBasicInfo(snmp, target, ip);
                if (device != null) {
                    getDetails(snmp, target, device);
                    getSupplies(snmp, target, device);
                    return device;
                }
            } catch (IOException e) {
                log.trace("IO error scanning {}: {}", ip, e.getMessage());
            }
        }
        return null;
    }

    private CommunityTarget createTarget(String ip, String community) {
        CommunityTarget target = new CommunityTarget();
        target.setCommunity(new OctetString(community));
        target.setAddress(new UdpAddress(ip + "/161"));
        target.setTimeout(1000);
        target.setRetries(1);
        target.setVersion(SnmpConstants.version2c);
        return target;
    }

    private PrinterDevice getBasicInfo(Snmp snmp, CommunityTarget target, String ip) throws IOException {
        PDU pdu = new PDU();
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SYSTEM_DESCRIPTION)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SYSTEM_NAME)));
        pdu.setType(PDU.GET);

        ResponseEvent response = snmp.send(pdu, target);
        if (response == null || response.getResponse() == null) return null;

        PrinterDevice.PrinterDeviceBuilder builder = PrinterDevice.builder().ipAddress(ip);
        for (VariableBinding vb : response.getResponse().getVariableBindings()) {
            String oid = vb.getOid().toString();
            String value = vb.getVariable().toString();

            if (oid.equals(PrinterDiscoveryConfig.SYSTEM_DESCRIPTION)) {
                builder.systemDescription(value);
            } else if (oid.equals(PrinterDiscoveryConfig.SYSTEM_NAME)) {
                builder.systemName(value);
            }
        }
        return builder.build();
    }

    private void getDetails(Snmp snmp, CommunityTarget target, PrinterDevice device) throws IOException {
        PDU pdu = new PDU();
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.IF_PHYSICAL_ADDRESS)));
        pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_MODEL)));
        pdu.setType(PDU.GET);

        ResponseEvent response = snmp.send(pdu, target);
        if (response == null || response.getResponse() == null) return;

        for (VariableBinding vb : response.getResponse().getVariableBindings()) {
            String oid = vb.getOid().toString();
            Variable var = vb.getVariable();

            if (oid.equals(PrinterDiscoveryConfig.IF_PHYSICAL_ADDRESS) && var instanceof OctetString) {
                device.setMacAddress(formatMac((OctetString) var));
            } else if (oid.equals(PrinterDiscoveryConfig.PRINTER_MODEL)) {
                device.setModelName(var.toString());
            }
        }
    }

    private void getSupplies(Snmp snmp, CommunityTarget target, PrinterDevice device) {
        try {
            TableUtils utils = new TableUtils(snmp, new DefaultPDUFactory());
            OID[] columns = {
                    new OID(PrinterDiscoveryConfig.PRINTER_SUPPLY_DESCRIPTION),
                    new OID(PrinterDiscoveryConfig.PRINTER_SUPPLY_CURRENT_LEVEL),
                    new OID(PrinterDiscoveryConfig.PRINTER_SUPPLY_MAX_LEVEL)
            };

            for (TableEvent event : utils.getTable(target, columns, null, null)) {
                if (event.isError()) continue;

                VariableBinding[] vbs = event.getColumns();
                String desc = vbs[0].getVariable().toString();
                int current = vbs[1].getVariable().toInt();
                int max = vbs[2].getVariable().toInt();

                device.addSupply(desc, current, max, desc, "units");
            }
        } catch (Exception e) {
            log.debug("Failed to get supplies for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }

    private String formatMac(OctetString mac) {
        byte[] bytes = mac.getValue();
        if (bytes.length != 6) return mac.toString();
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5]);
    }
}