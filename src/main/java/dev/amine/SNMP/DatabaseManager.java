package dev.amine.SNMP;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DatabaseManager {
    private static final String DB_URL = "jdbc:mysql://196.179.82.171:3306/printer_monitor";
    private static final String DB_USER = "your_username";
    private static final String DB_PASSWORD = "your_password";

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            log.error("MySQL JDBC Driver not found", e);
        }
    }

    public Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", DB_USER);
        props.setProperty("password", DB_PASSWORD);
        props.setProperty("sslMode", "DISABLED"); // Use SSL in production
        return DriverManager.getConnection(DB_URL, props);
    }

    public void insertPrinterData(PrinterDevice printer) {
        String printerSql = "INSERT INTO printers (mac_address, model_name, ip_address, serial_number, vendor) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "ip_address = VALUES(ip_address), last_seen = NOW()";

        String statusSql = "INSERT INTO printer_status (printer_id, status) " +
                "VALUES ((SELECT id FROM printers WHERE mac_address = ?), ?)";

        String countersSql = "INSERT INTO printer_counters (printer_id, total_page_count, color_page_count, mono_page_count) " +
                "VALUES ((SELECT id FROM printers WHERE mac_address = ?), ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement printerStmt = conn.prepareStatement(printerSql);
             PreparedStatement statusStmt = conn.prepareStatement(statusSql);
             PreparedStatement countersStmt = conn.prepareStatement(countersSql)) {

            // Insert/Update printer basic info
            printerStmt.setString(1, printer.getMacAddress());
            printerStmt.setString(2, printer.getModelName());
            printerStmt.setString(3, printer.getIpAddress());
            printerStmt.setString(4, printer.getSerialNumber());
            printerStmt.setString(5, printer.getVendor());
            printerStmt.executeUpdate();

            // Insert status
            statusStmt.setString(1, printer.getMacAddress());
            statusStmt.setString(2, printer.getStatus().name());
            statusStmt.executeUpdate();

            // Insert counters
            countersStmt.setString(1, printer.getMacAddress());
            countersStmt.setLong(2, printer.getTotalPageCount());
            countersStmt.setLong(3, printer.getColorPageCount());
            countersStmt.setLong(4, printer.getMonoPageCount());
            countersStmt.executeUpdate();

            // Insert supplies
            insertSupplies(conn, printer);
            insertTrays(conn, printer);

        } catch (SQLException e) {
            log.error("Database error", e);
        }
    }

    private void insertSupplies(Connection conn, PrinterDevice printer) throws SQLException {
        String sql = "INSERT INTO printer_supplies (printer_id, supply_name, current_level, max_level, percentage) " +
                "VALUES ((SELECT id FROM printers WHERE mac_address = ?), ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Integer> entry : printer.getSupplyLevels().entrySet()) {
                String supplyName = entry.getKey();
                Integer current = entry.getValue();
                Integer max = printer.getSupplyMaxLevels().get(supplyName);
                Integer percentage = printer.getSupplyPercentage(supplyName);

                stmt.setString(1, printer.getMacAddress());
                stmt.setString(2, supplyName);
                stmt.setInt(3, current);
                stmt.setInt(4, max);
                stmt.setInt(5, percentage);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void insertTrays(Connection conn, PrinterDevice printer) throws SQLException {
        String sql = "INSERT INTO paper_trays (printer_id, tray_name, current_level, max_level, percentage) " +
                "VALUES ((SELECT id FROM printers WHERE mac_address = ?), ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Map.Entry<String, Integer> entry : printer.getTrayLevels().entrySet()) {
                String trayName = entry.getKey();
                Integer current = entry.getValue();
                Integer max = printer.getTrayMaxLevels().get(trayName);
                Integer percentage = printer.getTrayPercentage(trayName);

                stmt.setString(1, printer.getMacAddress());
                stmt.setString(2, trayName);
                stmt.setInt(3, current);
                stmt.setInt(4, max);
                stmt.setInt(5, percentage);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public boolean checkCounterIncrease(String macAddress, Long newTotal, Long newColor, Long newMono) {
        String sql = "SELECT total_page_count, color_page_count, mono_page_count " +
                "FROM printer_counters " +
                "WHERE printer_id = (SELECT id FROM printers WHERE mac_address = ?) " +
                "ORDER BY timestamp DESC LIMIT 1";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, macAddress);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                Long oldTotal = rs.getLong("total_page_count");
                Long oldColor = rs.getLong("color_page_count");
                Long oldMono = rs.getLong("mono_page_count");

                return newTotal >= oldTotal &&
                        newColor >= oldColor &&
                        newMono >= oldMono;
            }
            return true; // No previous record
        } catch (SQLException e) {
            log.error("Counter validation failed", e);
            return false;
        }
    }
}