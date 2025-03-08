package dev.amine.SNMP;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class PrinterDiscoveryManager {
    private final ExecutorService scanExecutor;

    public PrinterDiscoveryManager() {
        this.scanExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors())
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

                // Get basic printer info first
                PrinterDevice printer = getBasicPrinterInfo(snmp, target, ipAddress);

                if (printer != null) {
                    // If we found a printer, get detailed information
                    getDetailedPrinterInfo(snmp, target, printer);
                    getPrinterSupplies(snmp, target, printer);
                    return printer;
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
        target.setTimeout(1500);
        target.setVersion(SnmpConstants.version2c);
        return target;
    }

    private PrinterDevice getBasicPrinterInfo(Snmp snmp, CommunityTarget target, String ipAddress) {
        try {
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SYSTEM_DESCRIPTION)));
            pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SYSTEM_NAME)));
            pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.SYSTEM_LOCATION)));
            pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_MODEL)));
            pdu.setType(PDU.GET);

            ResponseEvent response = snmp.send(pdu, target);

            if (isValidPrinterResponse(response)) {
                PrinterDevice.PrinterDeviceBuilder builder = PrinterDevice.builder()
                        .ipAddress(ipAddress);

                PDU responsePDU = response.getResponse();
                for (VariableBinding vb : responsePDU.getVariableBindings()) {
                    String oid = vb.getOid().toString();
                    String value = vb.getVariable().toString();

                    if (value.equals("noSuchObject") || value.equals("noSuchInstance")) {
                        continue;
                    }

                    switch (oid) {
                        case PrinterDiscoveryConfig.SYSTEM_DESCRIPTION:
                            builder.systemDescription(value);
                            break;
                        case PrinterDiscoveryConfig.SYSTEM_NAME:
                            builder.systemName(value);
                            break;
                        case PrinterDiscoveryConfig.SYSTEM_LOCATION:
                            builder.systemLocation(value);
                            break;
                        case PrinterDiscoveryConfig.PRINTER_MODEL:
                            builder.modelName(value);
                            break;
                    }
                }

                // Only return if we have confirmed it's a printer by having model name or description
                if (builder.build().getModelName() != null || builder.build().getSystemDescription() != null) {
                    return builder.build();
                }
            }
        } catch (IOException e) {
            log.trace("Error getting basic printer info for {}", ipAddress, e);
        }
        return null;
    }

    private void getDetailedPrinterInfo(Snmp snmp, CommunityTarget target, PrinterDevice printer) {
        try {
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.IF_PHYSICAL_ADDRESS)));
            pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_HR_DEVICE_STATUS)));
            pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_LIFETIME_COUNT)));
            pdu.add(new VariableBinding(new OID(PrinterDiscoveryConfig.PRINTER_CONSOLE_DISPLAY)));
            pdu.setType(PDU.GET);

            ResponseEvent response = snmp.send(pdu, target);

            if (response != null && response.getResponse() != null) {
                PDU responsePDU = response.getResponse();
                for (VariableBinding vb : responsePDU.getVariableBindings()) {
                    String oid = vb.getOid().toString();
                    Variable var = vb.getVariable();

                    if (var.toString().equals("noSuchObject") || var.toString().equals("noSuchInstance")) {
                        continue;
                    }

                    switch (oid) {
                        case PrinterDiscoveryConfig.IF_PHYSICAL_ADDRESS:
                            if (var instanceof OctetString) {
                                printer.setMacAddress(formatMacAddress((OctetString) var));
                            }
                            break;
                        case PrinterDiscoveryConfig.PRINTER_HR_DEVICE_STATUS:
                            if (var instanceof Integer32) {
                                int status = ((Integer32) var).getValue();
                                printer.setStatus(mapPrinterStatus(status));
                                printer.setStatusMessage(getStatusMessage(status));
                            }
                            break;
                        case PrinterDiscoveryConfig.PRINTER_LIFETIME_COUNT:
                            if (var instanceof Counter32 || var instanceof Integer32) {
                                printer.setTotalPageCount(var.toLong());
                            }
                            break;
                        case PrinterDiscoveryConfig.PRINTER_CONSOLE_DISPLAY:
                            printer.setConsoleMessage(var.toString());
                            break;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Error getting detailed printer info for {}", printer.getIpAddress(), e);
        }
    }

    private void getPrinterSupplies(Snmp snmp, CommunityTarget target, PrinterDevice printer) {
        try {
            // Set up SNMP table utils for supply table walk
            TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory(PDU.GETNEXT));

            // Columns to retrieve: description, unit, max level, current level
            OID[] columns = new OID[] {
                    new OID(PrinterDiscoveryConfig.PRINTER_SUPPLY_DESCRIPTION),
                    new OID(PrinterDiscoveryConfig.PRINTER_SUPPLY_UNIT),
                    new OID(PrinterDiscoveryConfig.PRINTER_SUPPLY_MAX_LEVEL),
                    new OID(PrinterDiscoveryConfig.PRINTER_SUPPLY_CURRENT_LEVEL)
            };

            List<TableEvent> events = tableUtils.getTable(target, columns, null, null);

            for (TableEvent event : events) {
                if (event.isError()) {
                    continue;
                }

                VariableBinding[] vbs = event.getColumns();
                if (vbs == null) {
                    continue;
                }

                String description = null;
                String unit = null;
                Integer maxLevel = null;
                Integer currentLevel = null;

                // Extract the supply index from the OID
                OID indexOID = event.getIndex();
                int supplyIndex = indexOID.last();

                for (VariableBinding vb : vbs) {
                    if (vb == null) continue;

                    String oid = vb.getOid().toString();
                    Variable var = vb.getVariable();

                    if (oid.startsWith(PrinterDiscoveryConfig.PRINTER_SUPPLY_DESCRIPTION)) {
                        description = var.toString();
                    } else if (oid.startsWith(PrinterDiscoveryConfig.PRINTER_SUPPLY_UNIT)) {
                        if (var instanceof Integer32) {
                            unit = mapSupplyUnit(((Integer32) var).getValue());
                        }
                    } else if (oid.startsWith(PrinterDiscoveryConfig.PRINTER_SUPPLY_MAX_LEVEL)) {
                        if (var instanceof Integer32) {
                            maxLevel = ((Integer32) var).getValue();
                        }
                    } else if (oid.startsWith(PrinterDiscoveryConfig.PRINTER_SUPPLY_CURRENT_LEVEL)) {
                        if (var instanceof Integer32) {
                            currentLevel = ((Integer32) var).getValue();
                        }
                    }
                }

                // Determine supply name based on index/description
                String supplyName = determineSupplyName(supplyIndex, description);

                // Add the supply to the printer
                printer.addSupply(supplyName, currentLevel, maxLevel, description, unit);
            }
        } catch (Exception e) {
            log.debug("Error getting printer supplies for {}", printer.getIpAddress(), e);
        }
    }

    private boolean isValidPrinterResponse(ResponseEvent response) {
        return response != null
                && response.getResponse() != null
                && !response.getResponse().getVariableBindings().isEmpty();
    }

    private String formatMacAddress(OctetString macOctetString) {
        byte[] macBytes = macOctetString.getValue();
        if (macBytes.length >= 6) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02X", macBytes[i] & 0xff));
                if (i < 5) sb.append(":");
            }
            return sb.toString();
        }
        return macOctetString.toString();
    }

    private PrinterStatus mapPrinterStatus(int statusCode) {
        // Map based on Host Resources MIB hrDeviceStatus values
        switch (statusCode) {
            case 1: // running
            case 2: // running but warning
                return PrinterStatus.RUNNING;
            case 3: // testing
                return PrinterStatus.TESTING;
            case 5: // down
                return PrinterStatus.DOWN;
            case 4: // warning
                return PrinterStatus.WARNING;
            default:
                return PrinterStatus.UNKNOWN;
        }
    }

    private String getStatusMessage(int statusCode) {
        switch (statusCode) {
            case 1: return "Running";
            case 2: return "Running with warning";
            case 3: return "Testing";
            case 4: return "Warning";
            case 5: return "Down";
            default: return "Unknown status (" + statusCode + ")";
        }
    }

    private String mapSupplyUnit(int unitCode) {
        // Mapping based on RFC 1759 supply unit values
        switch (unitCode) {
            case 3: return "supplyTenThousandthsOfInches";
            case 4: return "supplyHundredthsOfMillimeters";
            case 7: return "supplyTonerCartridge";
            case 8: return "supplyInkCartridge";
            case 9: return "supplySolidWaxCartridge";
            case 11: return "supplyRibbonWax";
            case 12: return "supplyRibbonResin";
            case 13: return "supplyWasteToner";
            case 14: return "supplyWasteInk";
            case 15: return "supplyWasteRibbon";
            case 16: return "supplyWasteToner";
            case 17: return "supplyFuser";
            case 18: return "supplyCoronaPrimary";
            case 19: return "supplyCoronaTransfer";
            case 20: return "supplyTransferBelt";
            case 21: return "supplyTransferRoller";
            case 22: return "supplyPickupRoller";
            case 23: return "supplyFuserOil";
            case 24: return "supplyWaterAdditive";
            case 25: return "supplyWasteWaterTank";
            case 26: return "supplyCleaner";
            case 27: return "supplyDeveloper";
            default: return "supplyOther(" + unitCode + ")";
        }
    }

    private String determineSupplyName(int supplyIndex, String description) {
        // First try to detect from description
        if (description != null) {
            description = description.toLowerCase();
            if (description.contains("black") || description.contains("noir") || description.contains("negro")) {
                return "Black";
            } else if (description.contains("cyan") || description.contains("blue") || description.contains("bleu")) {
                return "Cyan";
            } else if (description.contains("magenta") || description.contains("red") || description.contains("rouge")) {
                return "Magenta";
            } else if (description.contains("yellow") || description.contains("jaune") || description.contains("amarillo")) {
                return "Yellow";
            } else if (description.contains("waste") || description.contains("residuo") || description.contains("déchet")) {
                return "Waste";
            } else if (description.contains("drum") || description.contains("tambour") || description.contains("tambor")) {
                return "Drum";
            } else if (description.contains("fuser") || description.contains("fusor")) {
                return "Fuser";
            }
        }

        // If description doesn't help, try to use index
        switch (supplyIndex) {
            case PrinterDiscoveryConfig.BLACK_TONER_INDEX:
                return "Black";
            case PrinterDiscoveryConfig.CYAN_TONER_INDEX:
                return "Cyan";
            case PrinterDiscoveryConfig.MAGENTA_TONER_INDEX:
                return "Magenta";
            case PrinterDiscoveryConfig.YELLOW_TONER_INDEX:
                return "Yellow";
            default:
                return "Supply_" + supplyIndex;
        }
    }
}