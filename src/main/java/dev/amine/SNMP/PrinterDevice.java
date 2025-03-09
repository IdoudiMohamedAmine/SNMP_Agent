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
    private String macAddress;
    private String systemName;
    private String systemLocation;
    private String systemDescription;
    private String modelName;
    private String consoleMessage;
    private PrinterStatus status;
    private String statusMessage;
    private long totalPageCount;
    private Map<String, Integer> supplyLevels = new ConcurrentHashMap<>();
    private Map<String, Integer> supplyMaxLevels = new ConcurrentHashMap<>();
    private Map<String, String> supplyDescriptions = new ConcurrentHashMap<>();
    private Map<String, String> supplyTypes = new ConcurrentHashMap<>();
    private Map<String, String> additionalAttributes = new ConcurrentHashMap<>();

    public Integer getSupplyPercentage(String supplyName) {
        Integer currentLevel = supplyLevels.get(supplyName);
        Integer maxLevel = supplyMaxLevels.get(supplyName);
        if (currentLevel != null && maxLevel != null && maxLevel > 0) {
            return (int) (((double) currentLevel / maxLevel) * 100);
        }
        return null;
    }

    public void addSupply(String name, Integer level, Integer maxLevel, String description, String type) {
        if (level != null) supplyLevels.put(name, level);
        if (maxLevel != null) supplyMaxLevels.put(name, maxLevel);
        if (description != null) supplyDescriptions.put(name, description);
        if (type != null) supplyTypes.put(name, type);
    }
}