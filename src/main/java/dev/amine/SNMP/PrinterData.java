package dev.amine.SNMP;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class PrinterData {
    private String ipAddress;
    private String macAddress;
    private String model;
    private long totalPages;
    private final Map<String, Integer> tonerLevels=new HashMap<>();
    private final Map<String,String> additionalData=new HashMap<>();

    @Override
    public String toString(){
        return String.format("Printer [%s]%nMAC: %s%nModel: %s%nPages: %d%nToner: %s",
                ipAddress, macAddress, model, totalPages, tonerLevels);
    }
}
