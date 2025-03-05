package dev.amine.SNMP;

import lombok.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrinterDevice {
    private String ipAddress;
    private String systemDescription;
    private String printerModelName;
    private String printerSerialNumber;
    private String printerMacAddress;
    private PrinterStatus printerStatus;
    private int printerTotalPrintedPages;

    // New detailed toner levels
    private int blackTonerLevel;
    private int cyanTonerLevel;
    private int magentaTonerLevel;
    private int yellowTonerLevel;

    // Product Number and Serial Number details
    private String productNumber;
    private String serialNumber;

    private Map<String, String> additionalAttributes = new ConcurrentHashMap<>();
}