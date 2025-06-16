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

        // Handle null or empty model names by using a fallback
        if (modelName == null || modelName.trim().isEmpty()) {
            // Use serial number or IP as fallback model name
            String serialNumber = printer.getSerialNumber();
            if (serialNumber != null && !serialNumber.trim().isEmpty()) {
                modelName = "MODEL_" + serialNumber.trim().replaceAll("[^a-zA-Z0-9]", "_").toUpperCase();
            } else {
                modelName = "MODEL_" + printer.getIpAddress().replaceAll("[^a-zA-Z0-9]", "_").toUpperCase();
            }
            log.info("Generated model name '{}' for printer with IP {}", modelName, printer.getIpAddress());
        }

        // Ensure the tarif exists for this model
        if (!tarifExists(modelName)) {
            createDefaultTarif(modelName);
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
            pstmt.setObject(7, null); // No client assigned by default

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

    /**
     * Check if a tarif exists for the given model name
     */
    private boolean tarifExists(String modelName) {
        String sql = "SELECT COUNT(*) FROM tarifs WHERE model_name = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, modelName);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("Error checking if tarif exists for model {}: {}", modelName, e.getMessage());
        }

        return false;
    }

    /**
     * Create a default tarif entry for a new model
     */
    private void createDefaultTarif(String modelName) {
        String sql = """
        INSERT INTO tarifs (model_name, bw_print_price, colored_print_price, a3_print_price, a4_print_price)
        VALUES (?, ?, ?, ?, ?)
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, modelName);
            pstmt.setDouble(2, DEFAULT_BW_PRICE);
            pstmt.setDouble(3, DEFAULT_COLOR_PRICE);
            pstmt.setDouble(4, DEFAULT_A3_PRICE);
            pstmt.setDouble(5, DEFAULT_A4_PRICE);

            int rowsAffected = pstmt.executeUpdate();
            if (rowsAffected > 0) {
                log.info("Created default tarif for new model: {} with prices (BW: {}, Color: {}, A3: {}, A4: {})",
                        modelName, DEFAULT_BW_PRICE, DEFAULT_COLOR_PRICE, DEFAULT_A3_PRICE, DEFAULT_A4_PRICE);
            }
        } catch (SQLException e) {
            log.error("Error creating default tarif for model {}: {}", modelName, e.getMessage());
            // Don't throw exception here to avoid breaking printer insertion
        }
    }

    /**
     * Update tarif pricing for a specific model (optional utility method)
     */
    public boolean updateTarif(String modelName, double bwPrice, double colorPrice, double a3Price, double a4Price) {
        String sql = """
        UPDATE tarifs 
        SET bw_print_price = ?, colored_print_price = ?, a3_print_price = ?, a4_print_price = ?
        WHERE model_name = ?
        """;

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setDouble(1, bwPrice);
            pstmt.setDouble(2, colorPrice);
            pstmt.setDouble(3, a3Price);
            pstmt.setDouble(4, a4Price);
            pstmt.setString(5, modelName);

            int rowsAffected = pstmt.executeUpdate();
            if (rowsAffected > 0) {
                log.info("Updated tarif for model: {} with new prices", modelName);
                return true;
            }
        } catch (SQLException e) {
            log.error("Error updating tarif for model {}: {}", modelName, e.getMessage());
        }

        return false;
    }

    /**
     * Get all models and their tarifs (optional utility method)
     */
    public Map<String, Map<String, Double>> getAllTarifs() {
        Map<String, Map<String, Double>> tarifs = new HashMap<>();
        String sql = "SELECT model_name, bw_print_price, colored_print_price, a3_print_price, a4_print_price FROM tarifs";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                String modelName = rs.getString("model_name");
                Map<String, Double> prices = new HashMap<>();
                prices.put("bw_price", rs.getDouble("bw_print_price"));
                prices.put("color_price", rs.getDouble("colored_print_price"));
                prices.put("a3_price", rs.getDouble("a3_print_price"));
                prices.put("a4_price", rs.getDouble("a4_print_price"));
                tarifs.put(modelName, prices);
            }
        } catch (SQLException e) {
            log.error("Error retrieving all tarifs: {}", e.getMessage());
        }

        return tarifs;
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
}