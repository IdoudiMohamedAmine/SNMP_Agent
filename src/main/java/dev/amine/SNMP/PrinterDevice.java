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
    // Existing supply maps
    @Builder.Default private Map<String, Integer> supplyLevels = new ConcurrentHashMap<>();
    @Builder.Default private Map<String, Integer> supplyMaxLevels = new ConcurrentHashMap<>();
    @Builder.Default private Map<String, String> supplyDescriptions = new ConcurrentHashMap<>();

    // New paper tray maps
    @Builder.Default private Map<String, Integer> trayLevels = new ConcurrentHashMap<>();
    @Builder.Default private Map<String, Integer> trayMaxLevels = new ConcurrentHashMap<>();
    @Builder.Default private Map<String, String> trayDescriptions = new ConcurrentHashMap<>();

    // Basic info
    private String ipAddress;
    private String macAddress;
    private String modelName;
    private String serialNumber;

    // Page counts
    private long totalPageCount;
    private long colorPageCount;
    private long monoPageCount;

    // Status
    private PrinterStatus status;

    public Integer getSupplyPercentage(String supplyName) {
        Integer current = supplyLevels.get(supplyName);
        Integer max = supplyMaxLevels.get(supplyName);
        if (current == null || max == null || max <= 0) return null;
        return (int) ((double) current / max * 100);
    }

    public Integer getTrayPercentage(String trayName) {
        Integer current = trayLevels.get(trayName);
        Integer max = trayMaxLevels.get(trayName);
        if (current == null || max == null || max <= 0) return null;
        return (int) ((double) current / max * 100);
    }
}