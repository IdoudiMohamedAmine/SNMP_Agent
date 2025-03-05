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
    private Map<String, String> additionalAttributes=new ConcurrentHashMap<>();
}
