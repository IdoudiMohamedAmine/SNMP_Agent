package dev.amine.SNMP;

import lombok.extern.slf4j.Slf4j;
import java.sql.*;
import java.util.Map;

@Slf4j
public class DatabaseManager {
    private static final String JDBC_URL = "jdbc:mysql://196.179.82.171:3306/printwatch?useSSL=false";
    private static final String USER = "printwatch";
    private static final String PASSWORD = "securepassword";

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            log.error("MySQL JDBC Driver not found", e);
            System.exit(1);
        }
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
    }

    public static void savePrinterData(PrinterDevice printer) {
        String printerSql = "INSERT INTO printers (mac_address, model_name, vendor, serial_number, ip_address) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "ip_address = VALUES(ip_address), last_seen = CURRENT_TIMESTAMP";

        String countSql = "INSERT INTO printer_counts (printer_id, total_pages, color_pages, mono_pages) " +
                "VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement printerStmt = conn.prepareStatement(printerSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement countStmt = conn.prepareStatement(countSql)) {

            // Save printer basic info
            printerStmt.setString(1, printer.getMacAddress());
            printerStmt.setString(2, printer.getModelName());
            printerStmt.setString(3, printer.getVendor());
            printerStmt.setString(4, printer.getSerialNumber());
            printerStmt.setString(5, printer.getIpAddress());
            printerStmt.executeUpdate();

            // Get printer ID
            ResultSet rs = printerStmt.getGeneratedKeys();
            int printerId = -1;
            if (rs.next()) {
                printerId = rs.getInt(1);
            }

            if (printerId == -1) {
                // If no generated key, try to get existing ID
                printerId = getPrinterId(conn, printer.getMacAddress(), printer.getModelName());
            }

            // Save page counts
            if (printerId != -1) {
                countStmt.setInt(1, printerId);
                setLongOrNull(countStmt, 2, printer.getTotalPageCount());
                setLongOrNull(countStmt, 3, printer.getColorPageCount());
                setLongOrNull(countStmt, 4, printer.getMonoPageCount());
                countStmt.executeUpdate();

                // Save supplies
                saveSupplies(conn, printerId, printer);
                saveTrays(conn, printerId, printer);
                checkCounters(conn, printerId, printer);
                saveAlerts(conn, printerId, printer);
            }
        } catch (SQLException e) {
            log.error("Database error saving printer data: {}", e.getMessage());
        }
    }

    private static int getPrinterId(Connection conn, String mac, String model) throws SQLException {
        String sql = "SELECT id FROM printers WHERE mac_address = ? AND model_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, mac);
            stmt.setString(2, model);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static void saveSupplies(Connection conn, int printerId, PrinterDevice printer) throws SQLException {
        String sql = "INSERT INTO printer_supplies (printer_id, supply_name, current_level, max_level) " +
                "VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "current_level = VALUES(current_level), max_level = VALUES(max_level), last_updated = CURRENT_TIMESTAMP";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Integer> entry : printer.getSupplyLevels().entrySet()) {
                String supplyName = entry.getKey();
                Integer current = entry.getValue();
                Integer max = printer.getSupplyMaxLevels().get(supplyName);

                stmt.setInt(1, printerId);
                stmt.setString(2, supplyName);
                setIntOrNull(stmt, 3, current);
                setIntOrNull(stmt, 4, max);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private static void saveTrays(Connection conn, int printerId, PrinterDevice printer) throws SQLException {
        String sql = "INSERT INTO printer_trays (printer_id, tray_name, current_level, max_level) " +
                "VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "current_level = VALUES(current_level), max_level = VALUES(max_level), last_updated = CURRENT_TIMESTAMP";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Integer> entry : printer.getTrayLevels().entrySet()) {
                String trayName = entry.getKey();
                Integer current = entry.getValue();
                Integer max = printer.getTrayMaxLevels().get(trayName);

                stmt.setInt(1, printerId);
                stmt.setString(2, trayName);
                setIntOrNull(stmt, 3, current);
                setIntOrNull(stmt, 4, max);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private static void checkCounters(Connection conn, int printerId, PrinterDevice printer) throws SQLException {
        String sql = "SELECT total_pages FROM printer_counts " +
                "WHERE printer_id = ? " +
                "ORDER BY timestamp DESC LIMIT 1";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, printerId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                long lastTotal = rs.getLong("total_pages");
                if (printer.getTotalPageCount() != null && printer.getTotalPageCount() < lastTotal) {
                    log.warn("Counter rollback detected for printer {}! Current: {} Previous: {}",
                            printerId, printer.getTotalPageCount(), lastTotal);
                }
            }
        }
    }

    private static void saveAlerts(Connection conn, int printerId, PrinterDevice printer) throws SQLException {
        String sql = "INSERT INTO alerts (printer_id, alert_type, message) VALUES (?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            // Status alerts
            if (printer.getStatus() == PrinterStatus.DOWN) {
                stmt.setInt(1, printerId);
                stmt.setString(2, "STATUS");
                stmt.setString(3, "Printer is DOWN");
                stmt.addBatch();
            } else if (printer.getStatus() == PrinterStatus.WARNING) {
                stmt.setInt(1, printerId);
                stmt.setString(2, "STATUS");
                stmt.setString(3, "Printer has WARNING status");
                stmt.addBatch();
            }

            // Toner alerts
            if (printer.isLowToner()) {
                stmt.setInt(1, printerId);
                stmt.setString(2, "TONER");
                stmt.setString(3, "Low toner detected");
                stmt.addBatch();
            }

            // Paper alerts
            if (printer.isLowPaper()) {
                stmt.setInt(1, printerId);
                stmt.setString(2, "PAPER");
                stmt.setString(3, "Low paper detected");
                stmt.addBatch();
            }

            stmt.executeBatch();
        }
    }

    private static void setLongOrNull(PreparedStatement stmt, int index, Long value) throws SQLException {
        if (value != null) {
            stmt.setLong(index, value);
        } else {
            stmt.setNull(index, Types.BIGINT);
        }
    }

    private static void setIntOrNull(PreparedStatement stmt, int index, Integer value) throws SQLException {
        if (value != null) {
            stmt.setInt(index, value);
        } else {
            stmt.setNull(index, Types.INTEGER);
        }
    }
}