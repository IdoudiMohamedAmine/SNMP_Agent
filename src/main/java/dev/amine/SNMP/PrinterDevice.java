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

    // Paper tray maps
    @Builder.Default private Map<String, Integer> trayLevels = new ConcurrentHashMap<>();
    @Builder.Default private Map<String, Integer> trayMaxLevels = new ConcurrentHashMap<>();
    @Builder.Default private Map<String, String> trayDescriptions = new ConcurrentHashMap<>();

    // Basic info
    private String ipAddress;
    private String macAddress;
    private String modelName;
    private String serialNumber;
    private String vendor;
    private boolean colorPrinter;


    // Page counts
    private Long totalPageCount;
    private Long colorPageCount;
    private Long monoPageCount;

    // Status
    private PrinterStatus status;

    public void setModelName(String modelName) {
        if (modelName == null || modelName.isEmpty() ||
                modelName.equals("noSuchObject") || modelName.equals("noSuchInstance")) {
            this.modelName = "Unknown";
        } else {
            this.modelName = modelName;
        }
    }
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
        if (current < 0) return 0; // Handle negative values that some printers might report
        return (int) Math.min(100, (double) current / max * 100);
    }

    public boolean isLowToner() {
        return supplyLevels.entrySet().stream()
                .anyMatch(entry -> {
                    String name = entry.getKey().toLowerCase();
                    Integer percentage = getSupplyPercentage(entry.getKey());
                    return name.contains("toner") && percentage != null && percentage <= 10;
                });
    }

    public boolean isLowPaper() {
        return trayLevels.entrySet().stream()
                .anyMatch(entry -> {
                    Integer percentage = getTrayPercentage(entry.getKey());
                    return percentage != null && percentage <= 10;
                });
    }
    public boolean isColorPrinter() {
        return supplyDescriptions.values().stream()
                .anyMatch(desc -> desc.toLowerCase().matches(".*\\b(cyan|magenta|yellow)\\b.*"));
    }
    public void setColorPrinter(boolean colorPrinter) {
        this.colorPrinter = colorPrinter;
    }
}