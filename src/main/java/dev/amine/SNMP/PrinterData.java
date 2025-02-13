package dev.amine.SNMP;

import java.util.HashMap;
import java.util.Map;

public class PrinterData {
    private String ipAddress;
    private String macAddress;
    private String model;
    private long totalPages;
    private final Map<String, Integer> tonerLevels=new HashMap<>();
    private final Map<String,String> additionalData=new HashMap<>();

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(long totalPages) {
        this.totalPages = totalPages;
    }

    public Map<String, Integer> getTonerLevels() {
        return tonerLevels;
    }

    public Map<String, String> getAdditionalData() {
        return additionalData;
    }

    @Override
    public String toString(){
        return String.format("Printer [%s]%nMAC: %s%nModel: %s%nPages: %d%nToner: %s",
                ipAddress, macAddress, model, totalPages, tonerLevels);
    }
}
