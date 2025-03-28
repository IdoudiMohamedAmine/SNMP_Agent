package dev.amine.SNMP;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DatabaseManager {
    private static final String JDBC_URL = "jdbc:postgresql://printwatch-5450.jxf.gcp-europe-west3.cockroachlabs.cloud:26257/defaultdb?sslmode=verify-full";
    private static final String USER = "amine";
    private static final String PASSWORD = "XhFFQtOCKCg7jz5278rMCQ";

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("PostgreSQL JDBC Driver not found", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
    }

    public UUID upsertPrinter(PrinterDevice printer) {
        String sql = """
            INSERT INTO printers (mac_address, model_name, vendor, serial_number, ip_address, is_color)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (mac_address, model_name) DO UPDATE SET
                vendor = EXCLUDED.vendor,
                ip_address = EXCLUDED.ip_address,
                last_seen = now()
            RETURNING id
            """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, printer.getMacAddress());
            pstmt.setString(2, printer.getModelName());
            pstmt.setString(3, printer.getVendor());
            pstmt.setString(4, printer.getSerialNumber());
            pstmt.setString(5, printer.getIpAddress());
            pstmt.setBoolean(6, printer.isColorPrinter());

            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            log.error("Error upserting printer", e);
        }
        return null;
    }

    public void insertCounts(UUID printerId, PrinterDevice printer) {
        String checkSql = """
            SELECT total_pages, color_pages, mono_pages 
            FROM printer_counts 
            WHERE printer_id = ? 
            ORDER BY timestamp DESC 
            LIMIT 1
            """;

        String insertSql = """
            INSERT INTO printer_counts (printer_id, total_pages, color_pages, mono_pages)
            VALUES (?, ?, ?, ?)
            """;

        try (Connection conn = getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql);
             PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {

            // Check previous counts
            checkStmt.setObject(1, printerId);
            ResultSet rs = checkStmt.executeQuery();

            if (rs.next()) {
                long prevTotal = rs.getLong("total_pages");
                long prevColor = rs.getLong("color_pages");
                long prevMono = rs.getLong("mono_pages");

                if (printer.getTotalPageCount() < prevTotal ||
                        printer.getColorPageCount() < prevColor ||
                        printer.getMonoPageCount() < prevMono) {
                    log.warn("Page counts decreased for printer {} - possible counter reset", printerId);
                }
            }

            // Insert new counts
            insertStmt.setObject(1, printerId);
            insertStmt.setLong(2, printer.getTotalPageCount());
            insertStmt.setLong(3, printer.getColorPageCount());
            insertStmt.setLong(4, printer.getMonoPageCount());
            insertStmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Error inserting counts", e);
        }
    }

    public void upsertSupplies(UUID printerId, PrinterDevice printer) {
        String sql = """
            INSERT INTO printer_supplies (printer_id, supply_name, current_level, max_level)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (printer_id, supply_name) DO UPDATE SET
                current_level = EXCLUDED.current_level,
                max_level = EXCLUDED.max_level,
                last_updated = now()
            """;

        batchExecuteSupplies(printerId, printer.getSupplyLevels(), printer.getSupplyMaxLevels(), sql);
    }

    public void upsertTrays(UUID printerId, PrinterDevice printer) {
        String sql = """
            INSERT INTO printer_trays (printer_id, tray_name, current_level, max_level)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (printer_id, tray_name) DO UPDATE SET
                current_level = EXCLUDED.current_level,
                max_level = EXCLUDED.max_level,
                last_updated = now()
            """;

        batchExecuteSupplies(printerId, printer.getTrayLevels(), printer.getTrayMaxLevels(), sql);
    }

    private void batchExecuteSupplies(UUID printerId, Map<String, Integer> levels,
                                      Map<String, Integer> maxLevels, String sql) {
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (String name : levels.keySet()) {
                pstmt.setObject(1, printerId);
                pstmt.setString(2, name);
                pstmt.setInt(3, levels.get(name));
                pstmt.setInt(4, maxLevels.getOrDefault(name, 100)); // Default max to 100 if missing
                pstmt.addBatch();
            }

            pstmt.executeBatch();
        } catch (SQLException e) {
            log.error("Error upserting supplies/trays", e);
        }
    }

    public void insertAlerts(UUID printerId, List<String> alerts) {
        String sql = """
            INSERT INTO alerts (printer_id, alert_type, message)
            VALUES (?, ?, ?)
            ON CONFLICT (printer_id, alert_type) WHERE resolved = FALSE DO NOTHING
            """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (String alert : alerts) {
                String type = alert.startsWith("ALERT: Low toner") ? "TONER" :
                        alert.startsWith("ALERT: Low paper") ? "PAPER" : "STATUS";

                pstmt.setObject(1, printerId);
                pstmt.setString(2, type);
                pstmt.setString(3, alert);
                pstmt.addBatch();
            }

            pstmt.executeBatch();
        } catch (SQLException e) {
            log.error("Error inserting alerts", e);
        }
    }
    public List<String> getActiveAlerts() {
        List<String> alerts = new ArrayList<>();
        String sql = "SELECT p.mac_address, p.model_name, a.message " +
                "FROM alerts a " +
                "JOIN printers p ON a.printer_id = p.id " +
                "WHERE a.resolved = FALSE " +
                "ORDER BY a.timestamp DESC";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                String alert = String.format("[%s|%s] %s",
                        rs.getString("mac_address"),
                        rs.getString("model_name"),
                        rs.getString("message"));
                alerts.add(alert);
            }
        } catch (SQLException e) {
            log.error("Error retrieving alerts: {}", e.getMessage());
        }
        return alerts;
    }

    public void getHistoricalData(String macAddress, String modelName) {
        String printerId = getPrinterId(macAddress, modelName);
        if (printerId == null) {
            log.info("No historical data found for {} - {}", macAddress, modelName);
            return;
        }

        logCountHistory(printerId);
        logSupplyHistory(printerId);
        logTrayHistory(printerId);
    }

    private String getPrinterId(String macAddress, String modelName) {
        String sql = "SELECT id FROM printers WHERE mac_address = ? AND model_name = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, macAddress);
            pstmt.setString(2, modelName);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return rs.getString("id");
            }
        } catch (SQLException e) {
            log.error("Error finding printer ID: {}", e.getMessage());
        }
        return null;
    }

    private void logCountHistory(String printerId) {
        String sql = "SELECT timestamp, total_pages, color_pages, mono_pages " +
                "FROM printer_counts " +
                "WHERE printer_id = ? " +
                "ORDER BY timestamp DESC LIMIT 5";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, UUID.fromString(printerId));
            ResultSet rs = pstmt.executeQuery();

            log.info("--- Page Count History ---");
            while (rs.next()) {
                log.info("{} - Total: {}, Color: {}, Mono: {}",
                        rs.getTimestamp("timestamp"),
                        rs.getLong("total_pages"),
                        rs.getLong("color_pages"),
                        rs.getLong("mono_pages"));
            }
        } catch (SQLException e) {
            log.error("Error retrieving count history: {}", e.getMessage());
        }
    }

    private void logSupplyHistory(String printerId) {
        String sql = "SELECT supply_name, current_level, max_level, last_updated " +
                "FROM printer_supplies " +
                "WHERE printer_id = ? " +
                "ORDER BY last_updated DESC LIMIT 5";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, UUID.fromString(printerId));
            ResultSet rs = pstmt.executeQuery();

            log.info("\n--- Supply History ---");
            while (rs.next()) {
                int current = rs.getInt("current_level");
                int max = rs.getInt("max_level");
                int percent = (int) ((current / (double) max) * 100);

                log.info("{} - {}: {}/{} ({}%)",
                        rs.getTimestamp("last_updated"),
                        rs.getString("supply_name"),
                        current,
                        max,
                        percent);
            }
        } catch (SQLException e) {
            log.error("Error retrieving supply history: {}", e.getMessage());
        }
    }

    private void logTrayHistory(String printerId) {
        String sql = "SELECT tray_name, current_level, max_level, last_updated " +
                "FROM printer_trays " +
                "WHERE printer_id = ? " +
                "ORDER BY last_updated DESC LIMIT 5";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, UUID.fromString(printerId));
            ResultSet rs = pstmt.executeQuery();

            log.info("\n--- Tray History ---");
            while (rs.next()) {
                int current = rs.getInt("current_level");
                int max = rs.getInt("max_level");
                int percent = (int) ((current / (double) max) * 100);

                log.info("{} - {}: {}/{} sheets ({}%)",
                        rs.getTimestamp("last_updated"),
                        rs.getString("tray_name"),
                        current,
                        max,
                        percent);
            }
        } catch (SQLException e) {
            log.error("Error retrieving tray history: {}", e.getMessage());
        }
    }

}