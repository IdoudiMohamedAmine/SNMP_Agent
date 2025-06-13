package dev.amine.SNMP;

import java.sql.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DatabaseManager {
    private static final String JDBC_URL = "jdbc:postgresql://eu-central-1.5aeaa3b7-ea91-4f21-9d44-b6bd7e664a91.aws.yugabyte.cloud:5433/yugabyte";
    private static final String USER = "admin";
    private static final String PASSWORD = "39qby106r4u1Be3_yEKfr-t1gwptD0";

    private static final UUID DEFAULT_CLIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String DEFAULT_TARIF_MODEL = "DEFAULT_MODEL";

    static {
        try {
            Class.forName("org.postgresql.Driver");
            ensureDefaultEntitiesExist();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("PostgreSQL JDBC Driver not found", e);
        } catch (SQLException e) {
            log.error("Error initializing database defaults", e);
        }
    }

    private static void ensureDefaultEntitiesExist() throws SQLException {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            // Create default client
            String clientSql = "INSERT INTO CLIENT (id, email, password, role, company) " +
                    "VALUES (?, 'system@printwatch.local', 'systemPassword', 'SYSTEM', 'PrintWatch') " +
                    "ON CONFLICT (id) DO NOTHING";
            try (PreparedStatement stmt = conn.prepareStatement(clientSql)) {
                stmt.setObject(1, DEFAULT_CLIENT_ID);
                stmt.executeUpdate();
                log.debug("Default client ensured in database");
            }

            // Create default tarif model
            String tarifSql = "INSERT INTO TARIFS (model_name, bw_print_price, colored_print_price, a3_print_price, a4_print_price) " +
                    "VALUES (?, 0.05, 0.15, 0.20, 0.10) " +
                    "ON CONFLICT (model_name) DO NOTHING";
            try (PreparedStatement stmt = conn.prepareStatement(tarifSql)) {
                stmt.setString(1, DEFAULT_TARIF_MODEL);
                stmt.executeUpdate();
                log.debug("Default tarif model ensured in database");
            }
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
    }

    public UUID upsertPrinter(PrinterDevice printer) {
        String sql = """
        INSERT INTO PRINTER (model_name, serial_number, mac_address, ip_address, is_color, is_a3, client_id, tarif_model, last_updated)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (serial_number)
        DO UPDATE SET
            model_name = EXCLUDED.model_name,
            mac_address = EXCLUDED.mac_address,
            ip_address = EXCLUDED.ip_address,
            is_color = EXCLUDED.is_color,
            is_a3 = EXCLUDED.is_a3,
            last_updated = CURRENT_TIMESTAMP
        RETURNING id
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, printer.getModelName() != null ? printer.getModelName() : "Unknown Model");
            pstmt.setString(2, printer.getSerialNumber() != null ? printer.getSerialNumber() : "UNKNOWN_" + printer.getIpAddress());
            pstmt.setString(3, printer.getMacAddress());
            pstmt.setString(4, printer.getIpAddress());
            pstmt.setBoolean(5, printer.isColorPrinter());
            pstmt.setBoolean(6, printer.canPrintA3());
            pstmt.setObject(7, DEFAULT_CLIENT_ID);
            pstmt.setString(8, DEFAULT_TARIF_MODEL);

            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                UUID printerId = rs.getObject("id", UUID.class);
                log.debug("Printer upserted successfully with ID: {}", printerId);
                return printerId;
            }
        } catch (SQLException e) {
            log.error("Error upserting printer {}: {}", printer.getIpAddress(), e.getMessage(), e);
        }
        return null;
    }

    public void insertCounts(UUID printerId, PrinterDevice printer) {
        String sql = """
        INSERT INTO COUNTS (printer_id, time_of_update, total_prints)
        VALUES (?, CURRENT_TIMESTAMP, ?)
        ON CONFLICT (printer_id, time_of_update) DO UPDATE SET total_prints = EXCLUDED.total_prints
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, printerId);
            pstmt.setLong(2, printer.getTotalPageCount() != null ? printer.getTotalPageCount() : 0L);

            int rowsAffected = pstmt.executeUpdate();
            log.debug("Inserted/updated counts for printer {}: {} rows affected", printerId, rowsAffected);

        } catch (SQLException e) {
            log.error("Error inserting counts for printer {}: {}", printerId, e.getMessage(), e);
        }
    }

    public void insertSuppliesAndTrays(UUID printerId, PrinterDevice printer) {
        String sql = """
        INSERT INTO COMPONENTS (printer_id, time_of_update, supply_name, current_level, max_level, supply_type)
        VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?)
        ON CONFLICT (printer_id, time_of_update, supply_name) 
        DO UPDATE SET 
            current_level = EXCLUDED.current_level,
            max_level = EXCLUDED.max_level,
            supply_type = EXCLUDED.supply_type
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int batchCount = 0;

            // Insert supplies (toner/ink)
            if (printer.getSupplyLevels() != null && !printer.getSupplyLevels().isEmpty()) {
                for (Map.Entry<String, Integer> entry : printer.getSupplyLevels().entrySet()) {
                    String name = entry.getKey();
                    int currentLevel = entry.getValue() != null ? entry.getValue() : 0;
                    int maxLevel = printer.getSupplyMaxLevels().getOrDefault(name, 100);

                    pstmt.setObject(1, printerId);
                    pstmt.setString(2, name);
                    pstmt.setInt(3, currentLevel);
                    pstmt.setInt(4, maxLevel);
                    pstmt.setString(5, "SUPPLY");
                    pstmt.addBatch();
                    batchCount++;
                }
                log.debug("Added {} supplies to batch for printer {}", printer.getSupplyLevels().size(), printerId);
            }

            // Insert trays (paper)
            if (printer.getTrayLevels() != null && !printer.getTrayLevels().isEmpty()) {
                for (Map.Entry<String, Integer> entry : printer.getTrayLevels().entrySet()) {
                    String name = entry.getKey();
                    int currentLevel = entry.getValue() != null ? entry.getValue() : 0;
                    int maxLevel = printer.getTrayMaxLevels().getOrDefault(name, 100);

                    pstmt.setObject(1, printerId);
                    pstmt.setString(2, name);
                    pstmt.setInt(3, currentLevel);
                    pstmt.setInt(4, maxLevel);
                    pstmt.setString(5, "TRAY");
                    pstmt.addBatch();
                    batchCount++;
                }
                log.debug("Added {} trays to batch for printer {}", printer.getTrayLevels().size(), printerId);
            }

            // Execute batch if we have any components to insert
            if (batchCount > 0) {
                int[] results = pstmt.executeBatch();
                log.debug("Executed batch for printer {}: {} components processed, {} successful",
                        printerId, batchCount, Arrays.stream(results).sum());
            } else {
                log.debug("No components to insert for printer {}", printerId);
            }

        } catch (SQLException e) {
            log.error("Error inserting components for printer {}: {}", printerId, e.getMessage(), e);
        }
    }

    public void insertAlerts(UUID printerId, List<String> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            log.debug("No alerts to insert for printer {}", printerId);
            return;
        }

        String sql = """
        INSERT INTO ALERTS (id, time_of_update, alert_message, alert_type, printer_id)
        VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?)
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            int batchCount = 0;
            for (String alert : alerts) {
                String type = determineAlertType(alert);

                pstmt.setObject(1, UUID.randomUUID());
                pstmt.setString(2, alert);
                pstmt.setString(3, type);
                pstmt.setObject(4, printerId);
                pstmt.addBatch();
                batchCount++;
            }

            int[] results = pstmt.executeBatch();
            log.debug("Inserted {} alerts for printer {}: {} successful",
                    batchCount, printerId, Arrays.stream(results).sum());

        } catch (SQLException e) {
            log.error("Error inserting alerts for printer {}: {}", printerId, e.getMessage(), e);
        }
    }

    private String determineAlertType(String alert) {
        if (alert == null) return "GENERAL";

        String alertLower = alert.toLowerCase();
        if (alertLower.contains("low toner") || alertLower.contains("toner")) {
            return "TONER";
        } else if (alertLower.contains("low paper") || alertLower.contains("paper")) {
            return "PAPER";
        } else if (alertLower.contains("down") || alertLower.contains("offline")) {
            return "STATUS";
        } else if (alertLower.contains("warning")) {
            return "WARNING";
        } else {
            return "GENERAL";
        }
    }

    public List<String> getActiveAlerts() {
        List<String> alerts = new ArrayList<>();
        String sql = """
        SELECT p.mac_address, p.model_name, a.alert_message, a.time_of_update, a.alert_type
        FROM ALERTS a 
        JOIN PRINTER p ON a.printer_id = p.id 
        WHERE a.time_of_update >= CURRENT_TIMESTAMP - INTERVAL '3 days' 
        ORDER BY a.time_of_update DESC
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                String alert = String.format("[%s|%s|%s] %s",
                        rs.getString("mac_address") != null ? rs.getString("mac_address") : "Unknown MAC",
                        rs.getString("model_name") != null ? rs.getString("model_name") : "Unknown Model",
                        rs.getString("alert_type") != null ? rs.getString("alert_type") : "GENERAL",
                        rs.getString("alert_message"));
                alerts.add(alert);
            }

            log.debug("Retrieved {} active alerts from database", alerts.size());

        } catch (SQLException e) {
            log.error("Error retrieving alerts: {}", e.getMessage(), e);
        }
        return alerts;
    }

    /**
     * Get printer statistics for a specific printer
     */
    public Map<String, Object> getPrinterStats(UUID printerId) {
        Map<String, Object> stats = new HashMap<>();

        // Get latest counts
        String countsSql = """
        SELECT total_prints, time_of_update 
        FROM COUNTS 
        WHERE printer_id = ? 
        ORDER BY time_of_update DESC 
        LIMIT 1
        """;

        try (Connection conn = getConnection()) {
            // Get counts
            try (PreparedStatement pstmt = conn.prepareStatement(countsSql)) {
                pstmt.setObject(1, printerId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    stats.put("total_prints", rs.getLong("total_prints"));
                    stats.put("last_count_update", rs.getTimestamp("time_of_update"));
                }
            }

            // Get component counts
            String componentsSql = """
            SELECT COUNT(*) as component_count, supply_type
            FROM COMPONENTS 
            WHERE printer_id = ? 
            AND time_of_update = (
                SELECT MAX(time_of_update) 
                FROM COMPONENTS 
                WHERE printer_id = ?
            )
            GROUP BY supply_type
            """;

            try (PreparedStatement pstmt = conn.prepareStatement(componentsSql)) {
                pstmt.setObject(1, printerId);
                pstmt.setObject(2, printerId);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    stats.put(rs.getString("supply_type").toLowerCase() + "_count", rs.getInt("component_count"));
                }
            }

            // Get alert counts
            String alertsSql = """
            SELECT COUNT(*) as alert_count
            FROM ALERTS 
            WHERE printer_id = ? 
            AND time_of_update >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
            """;

            try (PreparedStatement pstmt = conn.prepareStatement(alertsSql)) {
                pstmt.setObject(1, printerId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    stats.put("recent_alerts", rs.getInt("alert_count"));
                }
            }

        } catch (SQLException e) {
            log.error("Error getting printer stats for {}: {}", printerId, e.getMessage(), e);
        }

        return stats;
    }

    /**
     * Clean up old data (older than specified days)
     */
    public void cleanupOldData(int daysToKeep) {
        String[] cleanupQueries = {
                "DELETE FROM ALERTS WHERE time_of_update < CURRENT_TIMESTAMP - INTERVAL '" + daysToKeep + " days'",
                "DELETE FROM COMPONENTS WHERE time_of_update < CURRENT_TIMESTAMP - INTERVAL '" + daysToKeep + " days'",
                "DELETE FROM COUNTS WHERE time_of_update < CURRENT_TIMESTAMP - INTERVAL '" + daysToKeep + " days'"
        };

        try (Connection conn = getConnection()) {
            for (String query : cleanupQueries) {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    int deleted = pstmt.executeUpdate();
                    log.debug("Cleaned up {} records with query: {}", deleted, query);
                }
            }
        } catch (SQLException e) {
            log.error("Error during cleanup: {}", e.getMessage(), e);
        }
    }
}