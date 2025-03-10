package dev.amine.SNMP;

public enum PrinterStatus {
    RUNNING,    // Normal operation
    WARNING,    // Needs attention but operational
    TESTING,    // In test/diagnostic mode
    DOWN,       // Out of service
    UNKNOWN;    // Status cannot be determined

    public static PrinterStatus fromStatusValue(int value) {
        return switch (value) {
            case 2 -> RUNNING;
            case 3 -> WARNING;
            case 4 -> TESTING;
            case 5 -> DOWN;
            default -> UNKNOWN;
        };
    }
}