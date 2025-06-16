package dev.amine.SNMP;

import java.sql.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DatabaseManager {
    private static final String JDBC_URL = "jdbc:postgresql://eu-central-1.5aeaa3b7-ea91-4f21-9d44-b6bd7e664a91.aws.yugabyte.cloud:5433/yugabyte";
    private static final String USER = "admin";
    private static final String PASSWORD = "39qby106r4u1Be3_yEKfr-t1gwptD0";

    // Default pricing constants
    private static final double DEFAULT_BW_PRICE = 0.05;
    private static final double DEFAULT_COLOR_PRICE = 0.15;
    private static final double DEFAULT_A3_PRICE = 0.20;
    private static final double DEFAULT_A4_PRICE = 0.10;

    static {
        try {
            Class.forName("org.postgresql.Driver");
            log.info("PostgreSQL JDBC Driver loaded successfully");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("PostgreSQL JDBC Driver not found", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
    }

    public UUID upsertPrinter(PrinterDevice printer) {
        String modelName = printer.getModelName();
        if (modelName == null || modelName.trim().isEmpty()) {
            modelName = "UNKNOWN_MODEL";
        }

        String sql = """
        INSERT INTO PRINTER (model_name, serial_number, mac_address, ip_address, is_color, is_a3, client_id, last_updated)
        VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
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

            pstmt.setString(1, modelName);
            pstmt.setString(2, printer.getSerialNumber() != null ? printer.getSerialNumber() : "UNKNOWN_" + printer.getIpAddress());
            pstmt.setString(3, printer.getMacAddress());
            pstmt.setString(4, printer.getIpAddress());
            pstmt.setBoolean(5, printer.isColorPrinter());
            pstmt.setBoolean(6, printer.canPrintA3());


            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                UUID printerId = rs.getObject("id", UUID.class);
                log.debug("Printer upserted successfully with ID: {} for model: {}", printerId, modelName);
                return printerId;
            }
        } catch (SQLException e) {
            log.error("Error upserting printer {} with model {}: {}", printer.getIpAddress(), modelName, e.getMessage(), e);
        }
        return null;
    }

    public void insertCounts(UUID printerId, PrinterDevice printer) {
        // Always insert new count record for historical tracking - no duplicate prevention
        String sql = """
        INSERT INTO COUNTS (printer_id, time_of_update, total_prints)
        VALUES (?, CURRENT_TIMESTAMP, ?)
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, printerId);
            pstmt.setLong(2, printer.getTotalPageCount() != null ? printer.getTotalPageCount() : 0L);

            int rowsAffected = pstmt.executeUpdate();
            log.debug("Archived new count record for printer {}: {} total prints", printerId,
                    printer.getTotalPageCount() != null ? printer.getTotalPageCount() : 0L);

        } catch (SQLException e) {
            log.error("Error archiving counts for printer {}: {}", printerId, e.getMessage(), e);
        }
    }

    public void insertSuppliesAndTrays(UUID printerId, PrinterDevice printer) {
        // Check if we already have recent component data (within last 30 minutes) to prevent duplicates
        if (hasRecentData(printerId, "COMPONENTS", 30)) {
            log.debug("Skipping components insert for printer {} - recent data exists", printerId);
            return;
        }

        // Use a single timestamp for all components in this batch
        Timestamp batchTimestamp = new Timestamp(System.currentTimeMillis());

        String sql = """
        INSERT INTO COMPONENTS (printer_id, time_of_update, supply_name, current_level, max_level, supply_type)
        VALUES (?, ?, ?, ?, ?, ?)
        """;

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false); // Use transaction to ensure all components are inserted together

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int batchCount = 0;

                // Insert supplies (toner/ink)
                if (printer.getSupplyLevels() != null && !printer.getSupplyLevels().isEmpty()) {
                    for (Map.Entry<String, Integer> entry : printer.getSupplyLevels().entrySet()) {
                        String name = entry.getKey();
                        int currentLevel = entry.getValue() != null ? entry.getValue() : 0;
                        int maxLevel = printer.getSupplyMaxLevels().getOrDefault(name, 100);

                        pstmt.setObject(1, printerId);
                        pstmt.setTimestamp(2, batchTimestamp);
                        pstmt.setString(3, name);
                        pstmt.setInt(4, currentLevel);
                        pstmt.setInt(5, maxLevel);
                        pstmt.setString(6, "SUPPLY");
                        pstmt.addBatch();
                        batchCount++;
                    }
                }

                // Insert trays (paper)
                if (printer.getTrayLevels() != null && !printer.getTrayLevels().isEmpty()) {
                    for (Map.Entry<String, Integer> entry : printer.getTrayLevels().entrySet()) {
                        String name = entry.getKey();
                        int currentLevel = entry.getValue() != null ? entry.getValue() : 0;
                        int maxLevel = printer.getTrayMaxLevels().getOrDefault(name, 100);

                        pstmt.setObject(1, printerId);
                        pstmt.setTimestamp(2, batchTimestamp);
                        pstmt.setString(3, name);
                        pstmt.setInt(4, currentLevel);
                        pstmt.setInt(5, maxLevel);
                        pstmt.setString(6, "TRAY");
                        pstmt.addBatch();
                        batchCount++;
                    }
                }

                // Execute batch if we have any components to insert
                if (batchCount > 0) {
                    int[] results = pstmt.executeBatch();
                    conn.commit();
                    int successCount = 0;
                    for (int result : results) {
                        if (result > 0) successCount++;
                    }
                    log.debug("Inserted {} components for printer {} (supplies: {}, trays: {})",
                            successCount, printerId,
                            printer.getSupplyLevels() != null ? printer.getSupplyLevels().size() : 0,
                            printer.getTrayLevels() != null ? printer.getTrayLevels().size() : 0);
                } else {
                    conn.commit();
                    log.debug("No components to insert for printer {}", printerId);
                }

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            log.error("Error inserting components for printer {}: {}", printerId, e.getMessage(), e);
        }
    }

    public void insertAlerts(UUID printerId, List<String> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return;
        }

        // Check for recent alerts to prevent spam
        if (hasRecentAlerts(printerId, 10)) {
            log.debug("Skipping alerts insert for printer {} - recent alerts exist", printerId);
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
            int successCount = 0;
            for (int result : results) {
                if (result > 0) successCount++;
            }
            log.debug("Inserted {} alerts for printer {}", successCount, printerId);

        } catch (SQLException e) {
            log.error("Error inserting alerts for printer {}: {}", printerId, e.getMessage(), e);
        }
    }

    private boolean hasRecentData(UUID printerId, String tableType, int minutesThreshold) {
        String sql;

        switch (tableType.toUpperCase()) {
            case "COUNTS":
                sql = """
                SELECT COUNT(*) FROM COUNTS 
                WHERE printer_id = ? 
                AND time_of_update >= CURRENT_TIMESTAMP - INTERVAL '%d minutes'
                """.formatted(minutesThreshold);
                break;
            case "COMPONENTS":
                sql = """
                SELECT COUNT(*) FROM COMPONENTS 
                WHERE printer_id = ? 
                AND time_of_update >= CURRENT_TIMESTAMP - INTERVAL '%d minutes'
                """.formatted(minutesThreshold);
                break;
            default:
                return false;
        }

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, printerId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return rs.getInt(1) > 0;
            }

        } catch (SQLException e) {
            log.error("Error checking recent data: {}", e.getMessage());
        }

        return false;
    }

    private boolean hasRecentAlerts(UUID printerId, int minutesThreshold) {
        String sql = """
        SELECT COUNT(*) FROM ALERTS 
        WHERE printer_id = ? 
        AND time_of_update >= CURRENT_TIMESTAMP - INTERVAL '%d minutes'
        """.formatted(minutesThreshold);

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, printerId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return rs.getInt(1) > 0;
            }

        } catch (SQLException e) {
            log.error("Error checking recent alerts: {}", e.getMessage());
        }

        return false;
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

        // Get latest count (most recent reading)
        String countsSql = """
        SELECT total_prints, time_of_update 
        FROM COUNTS 
        WHERE printer_id = ? 
        ORDER BY time_of_update DESC 
        LIMIT 1
        """;

        try (Connection conn = getConnection()) {
            // Get latest count
            try (PreparedStatement pstmt = conn.prepareStatement(countsSql)) {
                pstmt.setObject(1, printerId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    stats.put("total_prints", rs.getLong("total_prints"));
                    stats.put("last_count_update", rs.getTimestamp("time_of_update"));
                }
            }

            // Get count of historical records
            String countHistorySql = "SELECT COUNT(*) as count_records FROM COUNTS WHERE printer_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(countHistorySql)) {
                pstmt.setObject(1, printerId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    stats.put("total_count_records", rs.getInt("count_records"));
                }
            }

            // Calculate pages printed in last 24 hours
            String dailyPagesSql = """
            SELECT 
                MAX(total_prints) - MIN(total_prints) as pages_last_24h
            FROM COUNTS 
            WHERE printer_id = ? 
            AND time_of_update >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
            """;
            try (PreparedStatement pstmt = conn.prepareStatement(dailyPagesSql)) {
                pstmt.setObject(1, printerId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    long dailyPages = rs.getLong("pages_last_24h");
                    if (!rs.wasNull()) {
                        stats.put("pages_last_24h", dailyPages);
                    }
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
     * Get the latest count information for a printer
     */
    public Map<String, Object> getCurrentCount(UUID printerId) {
        Map<String, Object> countInfo = new HashMap<>();

        String sql = """
        SELECT total_prints, time_of_update 
        FROM COUNTS 
        WHERE printer_id = ? 
        ORDER BY time_of_update DESC 
        LIMIT 1
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, printerId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                countInfo.put("total_prints", rs.getLong("total_prints"));
                countInfo.put("time_of_update", rs.getTimestamp("time_of_update"));
            }

        } catch (SQLException e) {
            log.error("Error getting current count for printer {}: {}", printerId, e.getMessage(), e);
        }

        return countInfo;
    }

    /**
     * Get count history for a printer within a specified time range
     */
    public List<Map<String, Object>> getCountHistory(UUID printerId, int daysBack) {
        List<Map<String, Object>> history = new ArrayList<>();

        String sql = """
        SELECT total_prints, time_of_update 
        FROM COUNTS 
        WHERE printer_id = ? 
        AND time_of_update >= CURRENT_TIMESTAMP - INTERVAL '%d days'
        ORDER BY time_of_update ASC
        """.formatted(daysBack);

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, printerId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                Map<String, Object> record = new HashMap<>();
                record.put("total_prints", rs.getLong("total_prints"));
                record.put("time_of_update", rs.getTimestamp("time_of_update"));
                history.add(record);
            }

        } catch (SQLException e) {
            log.error("Error getting count history for printer {}: {}", printerId, e.getMessage(), e);
        }

        return history;
    }

    /**
     * Calculate pages printed since last reading
     */
    public Long getPagesSinceLastReading(UUID printerId) {
        String sql = """
        SELECT total_prints 
        FROM COUNTS 
        WHERE printer_id = ? 
        ORDER BY time_of_update DESC 
        LIMIT 2
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, printerId);
            ResultSet rs = pstmt.executeQuery();

            List<Long> lastTwoCounts = new ArrayList<>();
            while (rs.next() && lastTwoCounts.size() < 2) {
                lastTwoCounts.add(rs.getLong("total_prints"));
            }

            if (lastTwoCounts.size() == 2) {
                // Current count - previous count = pages printed
                return lastTwoCounts.get(0) - lastTwoCounts.get(1);
            }

        } catch (SQLException e) {
            log.error("Error calculating pages since last reading for printer {}: {}", printerId, e.getMessage(), e);
        }

        return null; // Not enough data or error
    }

    /**
     * Get pricing information for a printer model
     * Returns default prices if no specific pricing found in database
     */
    public Map<String, Double> getPrinterPricing(String modelName) {
        Map<String, Double> pricing = new HashMap<>();

        // First try to get pricing from TARIFS table if it exists
        String sql = "SELECT bw_print_price, colored_print_price, a3_print_price, a4_print_price " +
                "FROM TARIFS WHERE model_name = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, modelName);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                // Found specific pricing for this model
                pricing.put("bw_print_price", rs.getDouble("bw_print_price"));
                pricing.put("colored_print_price", rs.getDouble("colored_print_price"));
                pricing.put("a3_print_price", rs.getDouble("a3_print_price"));
                pricing.put("a4_print_price", rs.getDouble("a4_print_price"));
                log.debug("Found custom pricing for model: {}", modelName);
            } else {
                // No specific pricing found, use defaults
                setDefaultPricing(pricing);
                log.debug("Using default pricing for model: {}", modelName);
            }

        } catch (SQLException e) {
            log.warn("Error getting pricing for model {} (using defaults): {}", modelName, e.getMessage());
            // Use default pricing on any database error
            setDefaultPricing(pricing);
        }

        return pricing;
    }

    /**
     * Set default pricing values
     */
    private void setDefaultPricing(Map<String, Double> pricing) {
        pricing.put("bw_print_price", DEFAULT_BW_PRICE);
        pricing.put("colored_print_price", DEFAULT_COLOR_PRICE);
        pricing.put("a3_print_price", DEFAULT_A3_PRICE);
        pricing.put("a4_print_price", DEFAULT_A4_PRICE);
    }

    /**
     * Create a tarif record for a specific model (optional - only if needed)
     */
    public boolean createTarifForModel(String modelName, double bwPrice, double colorPrice, double a3Price, double a4Price) {
        String sql = """
        INSERT INTO TARIFS (model_name, bw_print_price, colored_print_price, a3_print_price, a4_print_price) 
        VALUES (?, ?, ?, ?, ?) 
        ON CONFLICT (model_name) 
        DO UPDATE SET
            bw_print_price = EXCLUDED.bw_print_price,
            colored_print_price = EXCLUDED.colored_print_price,
            a3_print_price = EXCLUDED.a3_print_price,
            a4_print_price = EXCLUDED.a4_print_price
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, modelName);
            pstmt.setDouble(2, bwPrice);
            pstmt.setDouble(3, colorPrice);
            pstmt.setDouble(4, a3Price);
            pstmt.setDouble(5, a4Price);

            int rowsAffected = pstmt.executeUpdate();
            log.info("Created/updated tarif for model {}: bw={}, color={}, a3={}, a4={}",
                    modelName, bwPrice, colorPrice, a3Price, a4Price);
            return rowsAffected > 0;

        } catch (SQLException e) {
            log.error("Error creating tarif for model {}: {}", modelName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Clean up old data with different retention periods
     * - COUNTS: Keep longer for historical analysis (default 365 days)
     * - COMPONENTS: Keep medium term (default specified days)
     * - ALERTS: Keep short term (default specified days)
     */
    public void cleanupOldData(int daysToKeep) {
        cleanupOldData(daysToKeep, 365); // Keep counts for 1 year by default
    }

    /**
     * Clean up old data with custom retention periods
     */
    public void cleanupOldData(int alertsAndComponentsDays, int countsDays) {
        String[] cleanupQueries = {
                "DELETE FROM ALERTS WHERE time_of_update < CURRENT_TIMESTAMP - INTERVAL '" + alertsAndComponentsDays + " days'",
                "DELETE FROM COMPONENTS WHERE time_of_update < CURRENT_TIMESTAMP - INTERVAL '" + alertsAndComponentsDays + " days'",
                "DELETE FROM COUNTS WHERE time_of_update < CURRENT_TIMESTAMP - INTERVAL '" + countsDays + " days'" // Keep counts longer
        };

        try (Connection conn = getConnection()) {
            for (String query : cleanupQueries) {
                try (PreparedStatement pstmt = conn.prepareStatement(query)) {
                    int deleted = pstmt.executeUpdate();
                    log.debug("Cleaned up {} records with query: {}", deleted, query);
                }
            }
            log.info("Cleanup completed: alerts/components kept for {} days, counts kept for {} days",
                    alertsAndComponentsDays, countsDays);
        } catch (SQLException e) {
            log.error("Error during cleanup: {}", e.getMessage(), e);
        }
    }
}