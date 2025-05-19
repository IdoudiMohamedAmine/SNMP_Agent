package dev.amine.SNMP;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DatabaseManager {
    // YugabyteDB connection details
    private static final String JDBC_URL = "jdbc:postgresql://eu-central-1.5aeaa3b7-ea91-4f21-9d44-b6bd7e664a91.aws.yugabyte.cloud:5433/yugabyte";
    private static final String USER = "admin";
    private static final String PASSWORD = "39qby106r4u1Be3_yEKfr-t1gwptD0";

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
        INSERT INTO printers (model_name, serial_number, mac_address, ip_address, is_color)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (mac_address) 
        DO UPDATE SET
            model_name = EXCLUDED.model_name,
            serial_number = EXCLUDED.serial_number,
            ip_address = EXCLUDED.ip_address,
            is_color = EXCLUDED.is_color,
            last_updated = now()
        RETURNING id
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, printer.getModelName());
            pstmt.setString(2, printer.getSerialNumber());
            pstmt.setString(3, printer.getMacAddress());
            pstmt.setString(4, printer.getIpAddress());
            pstmt.setBoolean(5, printer.isColorPrinter());

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
        String insertSql = """
        INSERT INTO counts (printer_id, total_prints)
        VALUES (?, ?)
        """;

        try (Connection conn = getConnection();
             PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {

            insertStmt.setObject(1, printerId);
            insertStmt.setLong(2, printer.getTotalPageCount());
            insertStmt.executeUpdate();

        } catch (SQLException e) {
            log.error("Error inserting counts", e);
        }
    }

    public void insertSuppliesAndTrays(UUID printerId, PrinterDevice printer) {
        String sql = """
            INSERT INTO components (printer_id, supply_name, type, current_level, max_level)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // Insert supplies
            for (String name : printer.getSupplyLevels().keySet()) {
                pstmt.setObject(1, printerId);
                pstmt.setString(2, name);
                pstmt.setString(3, "SUPPLY");
                pstmt.setInt(4, printer.getSupplyLevels().get(name));
                pstmt.setInt(5, printer.getSupplyMaxLevels().getOrDefault(name, 100));
                pstmt.addBatch();
            }

            // Insert trays
            for (String name : printer.getTrayLevels().keySet()) {
                pstmt.setObject(1, printerId);
                pstmt.setString(2, name);
                pstmt.setString(3, "TRAY");
                pstmt.setInt(4, printer.getTrayLevels().get(name));
                pstmt.setInt(5, printer.getTrayMaxLevels().getOrDefault(name, 100));
                pstmt.addBatch();
            }

            pstmt.executeBatch();
        } catch (SQLException e) {
            log.error("Error inserting components", e);
        }
    }

    public void insertAlerts(UUID printerId, List<String> alerts) {
        createAlertsTableIfNeeded();

        String sql = """
            INSERT INTO alerts (printer_id, alert_type, message, resolved)
            VALUES (?, ?, ?, false)
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

    private void createAlertsTableIfNeeded() {
        String sql = """
            CREATE TABLE IF NOT EXISTS alerts (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                printer_id UUID NOT NULL REFERENCES printers(id) ON DELETE CASCADE,
                alert_type VARCHAR(50) NOT NULL,
                message TEXT NOT NULL,
                timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                resolved BOOLEAN DEFAULT FALSE
            )
            """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            log.error("Error creating alerts table", e);
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
        String sql = "SELECT id FROM printers WHERE mac_address = ? AND model_name = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, macAddress);
            pstmt.setString(2, modelName);
            ResultSet rs = pstmt.executeQuery();

            if (!rs.next()) {
                log.info("No historical data found for {} - {}", macAddress, modelName);
                return;
            }

            UUID printerId = rs.getObject("id", UUID.class);
            logCountHistory(printerId);
            logComponentHistory(printerId, "SUPPLY");
            logComponentHistory(printerId, "TRAY");

        } catch (SQLException e) {
            log.error("Error finding printer ID: {}", e.getMessage());
        }
    }

    private void logCountHistory(UUID printerId) {
        String sql = "SELECT update_time, total_prints " +
                "FROM counts " +
                "WHERE printer_id = ? " +
                "ORDER BY update_time DESC LIMIT 5";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, printerId);
            ResultSet rs = pstmt.executeQuery();

            log.info("--- Page Count History ---");
            while (rs.next()) {
                log.info("{} - Total: {}",
                        rs.getTimestamp("update_time"),
                        rs.getLong("total_prints"));
            }
        } catch (SQLException e) {
            log.error("Error retrieving count history: {}", e.getMessage());
        }
    }

    private void logComponentHistory(UUID printerId, String type) {
        String sql = "SELECT supply_name, type, current_level, max_level, time_of_update " +
                "FROM components " +
                "WHERE printer_id = ? AND type = ? " +
                "ORDER BY time_of_update DESC, supply_name";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, printerId);
            pstmt.setString(2, type);
            ResultSet rs = pstmt.executeQuery();

            log.info("\n--- {} History ---", type);
            while (rs.next()) {
                int current = rs.getInt("current_level");
                int max = rs.getInt("max_level");
                int percent = max > 0 ? (int)((current / (double)max) * 100) : 0;

                log.info("{} - {}: {}/{} ({}%)",
                        rs.getTimestamp("time_of_update"),
                        rs.getString("supply_name"),
                        current,
                        max,
                        percent);
            }
        } catch (SQLException e) {
            log.error("Error retrieving component history: {}", e.getMessage());
        }
    }
}